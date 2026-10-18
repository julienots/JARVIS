package com.superessence.jarvis.sphere

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.superessence.jarvis.core.state.AssistantState
import com.superessence.jarvis.data.SphereStyle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Rendu OpenGL ES 2.0 de Jarvis : une sphère en relief animée par un bruit 3D,
 * entourée d'un halo, d'anneaux orbitaux et de particules. Chaque état de l'assistant
 * a sa propre « personnalité » (amplitude, vitesse, couleur), avec des transitions douces.
 */
class SphereRenderer(private val lowPower: Boolean, private val density: Float) : GLSurfaceView.Renderer {

    @Volatile var state: AssistantState = AssistantState.IDLE
    @Volatile var level: Float = 0f
    @Volatile var style: SphereStyle = SphereStyle.HOLOGRAM
    @Volatile var timeScale: Float = 1f
    @Volatile var onError: ((String) -> Unit)? = null

    // Rotation pilotée au doigt, avec inertie
    @Volatile private var dragVelX = 0f
    @Volatile private var dragVelY = 0f
    private var yaw = 0f
    private var pitch = 0.25f
    @Volatile private var tapFlash = 0f

    fun drag(dx: Float, dy: Float) { dragVelX += dx * 0.004f; dragVelY += dy * 0.004f }
    fun flash() { tapFlash = 1f }

    // Paramètres lissés
    private var amp = 0.12f; private var freq = 0.95f; private var speed = 0.6f; private var glow = 0.8f
    private var pulse = 0f; private var spread = 1f; private var smoothLevel = 0f
    private val colA = floatArrayOf(0.1f, 0.85f, 1f)
    private val colB = floatArrayOf(0.0f, 0.1f, 0.3f)
    private val colHalo = floatArrayOf(0.1f, 0.7f, 1f)
    private val colRing = floatArrayOf(0.5f, 0.95f, 1f)

    private var sphereProg = 0; private var haloProg = 0; private var ringProg = 0; private var partProg = 0
    private lateinit var sphereVerts: FloatBuffer
    private lateinit var sphereIdx: ShortBuffer
    private var sphereIdxCount = 0
    private lateinit var haloQuad: FloatBuffer
    private val rings = mutableListOf<Pair<FloatBuffer, Int>>()
    private lateinit var particles: FloatBuffer
    private val particleCount = if (lowPower) 90 else 220

    private val proj = FloatArray(16); private val view = FloatArray(16)
    private val model = FloatArray(16); private val mvp = FloatArray(16); private val tmp = FloatArray(16)
    private val camDist = 4.6f
    private var haloRadius = 0.5f
    private var aspect = 1f
    private var height = 1

    private var time = 0.0
    private var lastNs = 0L
    private var ok = true

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            sphereProg = program(Shaders.SPHERE_VS, Shaders.SPHERE_FS)
            haloProg = program(Shaders.HALO_VS, Shaders.HALO_FS)
            ringProg = program(Shaders.RING_VS, Shaders.RING_FS)
            partProg = program(Shaders.PART_VS, Shaders.PART_FS)
            buildSphere(if (lowPower) 32 else 56, if (lowPower) 48 else 84)
            haloQuad = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            rings.clear()
            rings += ring(1.42f, 0.010f)
            rings += ring(1.58f, 0.006f)
            rings += ring(1.30f, 0.004f)
            buildParticles()
            ok = true
        } catch (e: Exception) {
            ok = false
            onError?.invoke(e.message ?: "OpenGL indisponible")
        }
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        lastNs = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        this.height = height
        aspect = width.toFloat() / height.coerceAtLeast(1)
        var fovy = 45f
        if (aspect < 1f) fovy = Math.toDegrees(2.0 * atan(tan(Math.toRadians(22.5)) / aspect)).toFloat()
        Matrix.perspectiveM(proj, 0, fovy, aspect, 0.1f, 20f)
        Matrix.setLookAtM(view, 0, 0f, 0f, camDist, 0f, 0f, 0f, 0f, 1f, 0f)
        haloRadius = (1.0 / (camDist * tan(Math.toRadians(fovy / 2.0)))).toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!ok) return
        animate(dt)

        // Rotation : auto-rotation lente + inertie du doigt
        yaw += dt * (0.18f * speed) + dragVelX
        pitch = (pitch + dragVelY).coerceIn(-1.2f, 1.2f)
        val damp = exp(-dt * 2.5f)
        dragVelX *= damp; dragVelY *= damp
        pitch += (0.25f - pitch) * (1f - exp(-dt * 0.4f))

        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, Math.toDegrees(pitch.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.rotateM(model, 0, Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        val breath = 1f + 0.025f * sin(time * 1.3).toFloat() + smoothLevel * 0.06f + tapFlash * 0.05f
        Matrix.scaleM(model, 0, breath, breath, breath)
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)

        val t = time.toFloat()

        // 1. Halo (additif, sans profondeur)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glUseProgram(haloProg)
        GLES20.glUniform3fv(loc(haloProg, "uColor"), 1, colHalo, 0)
        GLES20.glUniform1f(loc(haloProg, "uTime"), t)
        GLES20.glUniform1f(loc(haloProg, "uGlow"), glow + tapFlash * 0.6f)
        GLES20.glUniform1f(loc(haloProg, "uPulse"), pulse)
        GLES20.glUniform1f(loc(haloProg, "uRadius"), haloRadius * breath)
        GLES20.glUniform1f(loc(haloProg, "uAspect"), aspect)
        val hp = attr(haloProg, "aPos")
        haloQuad.position(0)
        GLES20.glEnableVertexAttribArray(hp)
        GLES20.glVertexAttribPointer(hp, 2, GLES20.GL_FLOAT, false, 0, haloQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(hp)

        // 2. Sphère (alpha prémultiplié, avec profondeur)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(sphereProg)
        GLES20.glUniformMatrix4fv(loc(sphereProg, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(loc(sphereProg, "uModel"), 1, false, model, 0)
        GLES20.glUniform1f(loc(sphereProg, "uTime"), t * speed)
        GLES20.glUniform1f(loc(sphereProg, "uPhase"), (t % 1000f))
        GLES20.glUniform1f(loc(sphereProg, "uAmp"), amp + smoothLevel * 0.24f + tapFlash * 0.12f)
        GLES20.glUniform1f(loc(sphereProg, "uFreq"), freq)
        GLES20.glUniform3fv(loc(sphereProg, "uColorA"), 1, colA, 0)
        GLES20.glUniform3fv(loc(sphereProg, "uColorB"), 1, colB, 0)
        GLES20.glUniform3f(loc(sphereProg, "uCam"), 0f, 0f, camDist)
        GLES20.glUniform1f(loc(sphereProg, "uGlow"), glow + smoothLevel * 0.5f)
        GLES20.glUniform1f(loc(sphereProg, "uAlpha"), 0.96f)
        val sp = attr(sphereProg, "aPos")
        sphereVerts.position(0)
        GLES20.glEnableVertexAttribArray(sp)
        GLES20.glVertexAttribPointer(sp, 3, GLES20.GL_FLOAT, false, 0, sphereVerts)
        sphereIdx.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphereIdxCount, GLES20.GL_UNSIGNED_SHORT, sphereIdx)
        GLES20.glDisableVertexAttribArray(sp)

        // 3. Anneaux et particules (additifs, masqués par la sphère)
        GLES20.glDepthMask(false)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glUseProgram(ringProg)
        GLES20.glUniform3fv(loc(ringProg, "uColor"), 1, colRing, 0)
        GLES20.glUniform1f(loc(ringProg, "uTime"), t)
        val tilts = floatArrayOf(72f, 58f, 96f)
        val spins = floatArrayOf(0.35f, -0.22f, 0.5f)
        rings.forEachIndexed { i, (buf, count) ->
            val m = FloatArray(16); val mv = FloatArray(16); val ringMvp = FloatArray(16)
            Matrix.setIdentityM(m, 0)
            Matrix.rotateM(m, 0, Math.toDegrees(pitch.toDouble()).toFloat() * 0.5f, 1f, 0f, 0f)
            Matrix.rotateM(m, 0, tilts[i], 1f, 0.2f * i, 0f)
            Matrix.rotateM(m, 0, (t * spins[i] * 40f * speed) % 360f, 0f, 0f, 1f)
            Matrix.scaleM(m, 0, breath, breath, breath)
            Matrix.multiplyMM(mv, 0, view, 0, m, 0)
            Matrix.multiplyMM(ringMvp, 0, proj, 0, mv, 0)
            GLES20.glUniformMatrix4fv(loc(ringProg, "uMVP"), 1, false, ringMvp, 0)
            GLES20.glUniform1f(loc(ringProg, "uSpeed"), spins[i] * 0.6f * speed)
            GLES20.glUniform1f(loc(ringProg, "uAlpha"), (0.55f - i * 0.12f) * (0.5f + glow * 0.5f))
            val rp = attr(ringProg, "aPos"); val ra = attr(ringProg, "aAngle")
            buf.position(0)
            GLES20.glEnableVertexAttribArray(rp)
            GLES20.glVertexAttribPointer(rp, 3, GLES20.GL_FLOAT, false, 16, buf)
            buf.position(3)
            GLES20.glEnableVertexAttribArray(ra)
            GLES20.glVertexAttribPointer(ra, 1, GLES20.GL_FLOAT, false, 16, buf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, count)
            GLES20.glDisableVertexAttribArray(rp); GLES20.glDisableVertexAttribArray(ra)
        }

        GLES20.glUseProgram(partProg)
        val pm = FloatArray(16); val pmv = FloatArray(16); val pmvp = FloatArray(16)
        Matrix.setIdentityM(pm, 0)
        Matrix.rotateM(pm, 0, Math.toDegrees(pitch.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.rotateM(pm, 0, Math.toDegrees(yaw * 0.3).toFloat(), 0f, 1f, 0f)
        Matrix.multiplyMM(pmv, 0, view, 0, pm, 0)
        Matrix.multiplyMM(pmvp, 0, proj, 0, pmv, 0)
        GLES20.glUniformMatrix4fv(loc(partProg, "uMVP"), 1, false, pmvp, 0)
        GLES20.glUniform1f(loc(partProg, "uTime"), t * speed)
        GLES20.glUniform1f(loc(partProg, "uSize"), 3.4f * density * camDist * (height / 900f).coerceIn(0.5f, 1.6f))
        GLES20.glUniform1f(loc(partProg, "uSpread"), spread)
        GLES20.glUniform3fv(loc(partProg, "uColor"), 1, colRing, 0)
        val pa = attr(partProg, "aSeed")
        particles.position(0)
        GLES20.glEnableVertexAttribArray(pa)
        GLES20.glVertexAttribPointer(pa, 4, GLES20.GL_FLOAT, false, 0, particles)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, particleCount)
        GLES20.glDisableVertexAttribArray(pa)
        GLES20.glDepthMask(true)
    }

    /** Fait évoluer les paramètres vers ceux de l'état courant. */
    private fun animate(dt: Float) {
        time += dt * timeScale
        val k = 1f - exp(-dt * 3.5f)
        val st = state
        val target = Personality.of(st)
        amp += (target.amp - amp) * k
        freq += (target.freq - freq) * k
        speed += (target.speed - speed) * k
        glow += (target.glow - glow) * k
        pulse += (target.pulse - pulse) * k
        spread += (target.spread - spread) * k
        val lv = when (st) {
            AssistantState.LISTENING -> level
            // Pendant qu'il parle, la sphère « articule » même si la voix Android ne signale pas chaque mot.
            AssistantState.SPEAKING -> maxOf(level, 0.32f + 0.22f * sin(time * 9.0).toFloat() + 0.12f * sin(time * 23.0 + 1.3).toFloat())
            else -> 0f
        }
        smoothLevel += (lv - smoothLevel) * (1f - exp(-dt * 10f))
        tapFlash *= exp(-dt * 3f)
        val pal = Palette.of(style, st)
        lerp(colA, pal.a, k); lerp(colB, pal.b, k); lerp(colHalo, pal.halo, k); lerp(colRing, pal.ring, k)
    }

    private fun lerp(dst: FloatArray, src: FloatArray, k: Float) { for (i in 0..2) dst[i] += (src[i] - dst[i]) * k }

    // ---------- Géométrie ----------

    private fun buildSphere(lat: Int, lon: Int) {
        val v = FloatArray((lat + 1) * (lon + 1) * 3)
        var p = 0
        for (i in 0..lat) {
            val th = PI * i / lat
            for (j in 0..lon) {
                val ph = 2 * PI * j / lon
                v[p++] = (sin(th) * cos(ph)).toFloat()
                v[p++] = cos(th).toFloat()
                v[p++] = (sin(th) * sin(ph)).toFloat()
            }
        }
        val idx = ShortArray(lat * lon * 6)
        var q = 0
        for (i in 0 until lat) for (j in 0 until lon) {
            val a = i * (lon + 1) + j
            val b = a + lon + 1
            idx[q++] = a.toShort(); idx[q++] = b.toShort(); idx[q++] = (a + 1).toShort()
            idx[q++] = b.toShort(); idx[q++] = (b + 1).toShort(); idx[q++] = (a + 1).toShort()
        }
        sphereVerts = floats(v)
        sphereIdx = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(idx); position(0) }
        sphereIdxCount = idx.size
    }

    private fun ring(radius: Float, width: Float): Pair<FloatBuffer, Int> {
        val seg = if (lowPower) 96 else 180
        val data = FloatArray((seg + 1) * 2 * 4)
        var p = 0
        for (i in 0..seg) {
            val a = 2 * PI * i / seg
            for (r in floatArrayOf(radius - width, radius + width)) {
                data[p++] = (cos(a) * r).toFloat(); data[p++] = (sin(a) * r).toFloat(); data[p++] = 0f
                data[p++] = a.toFloat()
            }
        }
        return floats(data) to (seg + 1) * 2
    }

    private fun buildParticles() {
        val rnd = Random(42)
        val d = FloatArray(particleCount * 4)
        for (i in 0 until particleCount) {
            d[i * 4] = rnd.nextFloat() * 6.283f
            d[i * 4 + 1] = rnd.nextFloat() * 6.283f
            d[i * 4 + 2] = rnd.nextFloat()
            d[i * 4 + 3] = rnd.nextFloat()
        }
        particles = floats(d)
    }

    // ---------- Utilitaires GL ----------

    private val locCache = HashMap<String, Int>()
    private fun loc(prog: Int, name: String) = locCache.getOrPut("$prog/$name") { GLES20.glGetUniformLocation(prog, name) }
    private fun attr(prog: Int, name: String) = locCache.getOrPut("$prog#$name") { GLES20.glGetAttribLocation(prog, name) }

    private fun floats(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw IllegalStateException("Shader : $log")
        }
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw IllegalStateException("Programme : " + GLES20.glGetProgramInfoLog(p))
        return p
    }
}

/** Comportement de la sphère pour chaque état. */
data class Personality(val amp: Float, val freq: Float, val speed: Float, val glow: Float, val pulse: Float, val spread: Float) {
    companion object {
        fun of(s: AssistantState) = when (s) {
            AssistantState.IDLE -> Personality(0.12f, 0.95f, 0.55f, 0.8f, 0f, 1f)
            AssistantState.WAKE_DETECTED -> Personality(0.26f, 1.2f, 1.6f, 1.7f, 1f, 1.25f)
            AssistantState.LISTENING -> Personality(0.15f, 1.1f, 1.0f, 1.25f, 0.8f, 1.1f)
            AssistantState.THINKING -> Personality(0.22f, 1.7f, 2.1f, 1.15f, 0.25f, 0.8f)
            AssistantState.EXECUTING -> Personality(0.18f, 1.4f, 1.7f, 1.3f, 0.5f, 1.2f)
            AssistantState.SPEAKING -> Personality(0.14f, 1.05f, 1.1f, 1.2f, 0.15f, 1.05f)
            AssistantState.AWAITING_CONFIRMATION -> Personality(0.13f, 1.0f, 0.8f, 1.1f, 0.6f, 1f)
            AssistantState.ERROR -> Personality(0.09f, 2.0f, 0.9f, 0.9f, 0f, 0.7f)
            AssistantState.MIC_OFF -> Personality(0.05f, 0.8f, 0.25f, 0.35f, 0f, 0.6f)
        }
    }
}

data class Palette(val a: FloatArray, val b: FloatArray, val halo: FloatArray, val ring: FloatArray) {
    companion object {
        private fun rgb(hex: Int) = floatArrayOf(((hex shr 16) and 255) / 255f, ((hex shr 8) and 255) / 255f, (hex and 255) / 255f)

        fun base(style: SphereStyle) = when (style) {
            SphereStyle.HOLOGRAM -> Palette(rgb(0x22E4FF), rgb(0x04204F), rgb(0x0EA5FF), rgb(0x8DF6FF))
            SphereStyle.GOLD -> Palette(rgb(0xFFD02A), rgb(0x3A1E00), rgb(0xFFB000), rgb(0xFFF1B0))
            SphereStyle.NEBULA -> Palette(rgb(0xC77DFF), rgb(0x1A0640), rgb(0x7B2CFF), rgb(0xE7C6FF))
            SphereStyle.EMERALD -> Palette(rgb(0x2BFFB0), rgb(0x00301E), rgb(0x00D98B), rgb(0xB5FFE4))
        }

        fun of(style: SphereStyle, s: AssistantState): Palette = when (s) {
            AssistantState.THINKING -> Palette(rgb(0xB8A6FF), rgb(0x160A4A), rgb(0x6A5CFF), rgb(0xDCD4FF))
            AssistantState.EXECUTING -> Palette(rgb(0xFFD02A), rgb(0x2A1A00), rgb(0xFFB020), rgb(0xFFF0B8))
            AssistantState.AWAITING_CONFIRMATION -> Palette(rgb(0xFFAA33), rgb(0x2E1400), rgb(0xFF8A00), rgb(0xFFE0B0))
            AssistantState.ERROR -> Palette(rgb(0xFF4D5E), rgb(0x2A0008), rgb(0xFF2244), rgb(0xFFB3BC))
            AssistantState.MIC_OFF -> Palette(rgb(0x7C8A99), rgb(0x0B1016), rgb(0x3A4654), rgb(0x9AA6B2))
            else -> base(style)
        }
    }
}
