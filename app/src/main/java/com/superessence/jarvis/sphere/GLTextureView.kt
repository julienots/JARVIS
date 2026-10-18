package com.superessence.jarvis.sphere

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLSurfaceView
import android.view.TextureView

/**
 * Équivalent de GLSurfaceView bâti sur une TextureView : l'image OpenGL est composée avec le reste
 * de l'interface, sa transparence est donc réelle (aucun carré noir derrière la sphère, dans
 * l'application comme dans la bulle flottante). Le rendu tourne sur un thread dédié.
 */
open class GLTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    companion object {
        const val RENDERMODE_WHEN_DIRTY = 0
        const val RENDERMODE_CONTINUOUSLY = 1
    }

    private var renderer: GLSurfaceView.Renderer? = null
    private var thread: RenderThread? = null
    /** Appelé (depuis le thread de rendu) si EGL ne peut pas être initialisé. */
    var onEglFailure: ((String) -> Unit)? = null

    var renderMode: Int = RENDERMODE_CONTINUOUSLY
        set(value) { field = value; thread?.wake() }

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setRenderer(r: GLSurfaceView.Renderer) { renderer = r }

    fun requestRender() { thread?.requestRender() }

    fun onPause() { thread?.setPaused(true) }

    fun onResume() { thread?.setPaused(false) }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val r = renderer ?: return
        thread = RenderThread(surface, r, width, height).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        thread?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        thread?.finish()
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private inner class RenderThread(
        private val texture: SurfaceTexture,
        private val r: GLSurfaceView.Renderer,
        private var width: Int,
        private var height: Int,
    ) : Thread("JarvisGL") {
        private val lock = java.lang.Object()
        private var exit = false
        private var paused = false
        private var dirty = true
        private var sizeChanged = true

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        fun requestRender() = synchronized(lock) { dirty = true; lock.notifyAll() }
        fun wake() = synchronized(lock) { lock.notifyAll() }
        fun setPaused(p: Boolean) = synchronized(lock) { paused = p; dirty = true; lock.notifyAll() }
        fun resize(w: Int, h: Int) = synchronized(lock) { width = w; height = h; sizeChanged = true; dirty = true; lock.notifyAll() }

        fun finish() {
            synchronized(lock) { exit = true; lock.notifyAll() }
            try { join(1000) } catch (_: InterruptedException) {}
        }

        override fun run() {
            if (!initEgl()) { releaseEgl(); return }
            try {
                r.onSurfaceCreated(null, null)
                var lastFrame = 0L
                while (true) {
                    var w = 0; var h = 0; var changed = false
                    synchronized(lock) {
                        while (!exit && (paused || (renderMode == RENDERMODE_WHEN_DIRTY && !dirty))) lock.wait()
                        if (exit) return
                        w = width; h = height; changed = sizeChanged
                        sizeChanged = false; dirty = false
                    }
                    if (changed) r.onSurfaceChanged(null, w, h)
                    r.onDrawFrame(null)
                    if (!EGL14.eglSwapBuffers(display, surface)) return
                    // La TextureView ne cadence pas toujours sur l'écran : on vise 60 images/s.
                    val now = System.nanoTime()
                    val waitMs = 16 - (now - lastFrame) / 1_000_000
                    if (renderMode == RENDERMODE_CONTINUOUSLY && waitMs > 0) sleep(waitMs)
                    lastFrame = System.nanoTime()
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                onEglFailure?.invoke(e.message ?: "Erreur OpenGL")
            } finally {
                releaseEgl()
            }
        }

        private fun initEgl(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
                onEglFailure?.invoke("EGL indisponible"); return false
            }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0 || configs[0] == null) {
                onEglFailure?.invoke("Aucune configuration OpenGL compatible"); return false
            }
            val config = configs[0]
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) { onEglFailure?.invoke("Contexte OpenGL refusé"); return false }
            surface = try {
                EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            } catch (_: Exception) { EGL14.EGL_NO_SURFACE }
            if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                onEglFailure?.invoke("Surface OpenGL refusée"); return false
            }
            return true
        }

        private fun releaseEgl() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            // Pas d'eglTerminate : l'affichage EGL est partagé par tout le processus.
            EGL14.eglReleaseThread()
            display = EGL14.EGL_NO_DISPLAY
            surface = EGL14.EGL_NO_SURFACE
            context = EGL14.EGL_NO_CONTEXT
        }
    }
}
