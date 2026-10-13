package com.superessence.jarvis.sphere

/** Shaders GLSL ES 1.00 de la sphère holographique (compatibles OpenGL ES 2.0 et WebGL 1). */
object Shaders {

    // Bruit simplex 3D (Ashima Arts / Stefan Gustavson, licence MIT)
    private const val NOISE = """
vec3 mod289(vec3 x){return x-floor(x*(1.0/289.0))*289.0;}
vec4 mod289(vec4 x){return x-floor(x*(1.0/289.0))*289.0;}
vec4 permute(vec4 x){return mod289(((x*34.0)+1.0)*x);}
vec4 taylorInvSqrt(vec4 r){return 1.79284291400159-0.85373472095314*r;}
float snoise(vec3 v){
  const vec2 C=vec2(1.0/6.0,1.0/3.0);
  const vec4 D=vec4(0.0,0.5,1.0,2.0);
  vec3 i=floor(v+dot(v,C.yyy));
  vec3 x0=v-i+dot(i,C.xxx);
  vec3 g=step(x0.yzx,x0.xyz);
  vec3 l=1.0-g;
  vec3 i1=min(g.xyz,l.zxy);
  vec3 i2=max(g.xyz,l.zxy);
  vec3 x1=x0-i1+C.xxx;
  vec3 x2=x0-i2+C.yyy;
  vec3 x3=x0-D.yyy;
  i=mod289(i);
  vec4 p=permute(permute(permute(i.z+vec4(0.0,i1.z,i2.z,1.0))+i.y+vec4(0.0,i1.y,i2.y,1.0))+i.x+vec4(0.0,i1.x,i2.x,1.0));
  float n_=0.142857142857;
  vec3 ns=n_*D.wyz-D.xzx;
  vec4 j=p-49.0*floor(p*ns.z*ns.z);
  vec4 x_=floor(j*ns.z);
  vec4 y_=floor(j-7.0*x_);
  vec4 x=x_*ns.x+ns.yyyy;
  vec4 y=y_*ns.x+ns.yyyy;
  vec4 h=1.0-abs(x)-abs(y);
  vec4 b0=vec4(x.xy,y.xy);
  vec4 b1=vec4(x.zw,y.zw);
  vec4 s0=floor(b0)*2.0+1.0;
  vec4 s1=floor(b1)*2.0+1.0;
  vec4 sh=-step(h,vec4(0.0));
  vec4 a0=b0.xzyw+s0.xzyw*sh.xxyy;
  vec4 a1=b1.xzyw+s1.xzyw*sh.zzww;
  vec3 p0=vec3(a0.xy,h.x);
  vec3 p1=vec3(a0.zw,h.y);
  vec3 p2=vec3(a1.xy,h.z);
  vec3 p3=vec3(a1.zw,h.w);
  vec4 norm=taylorInvSqrt(vec4(dot(p0,p0),dot(p1,p1),dot(p2,p2),dot(p3,p3)));
  p0*=norm.x;p1*=norm.y;p2*=norm.z;p3*=norm.w;
  vec4 m=max(0.6-vec4(dot(x0,x0),dot(x1,x1),dot(x2,x2),dot(x3,x3)),0.0);
  m=m*m;
  return 42.0*dot(m*m,vec4(dot(p0,x0),dot(p1,x1),dot(p2,x2),dot(p3,x3)));
}
"""

    /** Sphère déformée par un bruit animé : le relief « respire » et réagit à la voix. */
    const val SPHERE_VS = """
uniform mat4 uMVP;
uniform mat4 uModel;
uniform float uTime;
uniform float uAmp;
uniform float uFreq;
attribute vec3 aPos;
varying vec3 vNormal;
varying vec3 vWorld;
varying float vDisp;
$NOISE
float disp(vec3 p){
  float a=snoise(p*uFreq+vec3(0.0,uTime*0.35,uTime*0.21));
  float b=snoise(p*uFreq*1.9-vec3(uTime*0.5,0.0,uTime*0.3));
  return a*0.8+b*0.2;
}
void main(){
  vec3 n=normalize(aPos);
  float d=disp(n);
  vec3 p=n*(1.0+uAmp*d);
  vec3 up=abs(n.y)<0.99?vec3(0.0,1.0,0.0):vec3(1.0,0.0,0.0);
  vec3 t=normalize(cross(n,up));
  vec3 b=cross(n,t);
  float e=0.03;
  vec3 nt=normalize(n+t*e);
  vec3 nb=normalize(n+b*e);
  vec3 pt=nt*(1.0+uAmp*disp(nt));
  vec3 pb=nb*(1.0+uAmp*disp(nb));
  vec3 nn=normalize(cross(pt-p,pb-p));
  if(dot(nn,n)<0.0) nn=-nn;
  vNormal=(uModel*vec4(nn,0.0)).xyz;
  vWorld=(uModel*vec4(p,1.0)).xyz;
  vDisp=d;
  gl_Position=uMVP*vec4(p,1.0);
}
"""

    const val SPHERE_FS = """
precision mediump float;
uniform vec3 uColorA;
uniform vec3 uColorB;
uniform vec3 uCam;
uniform float uPhase;
uniform float uGlow;
uniform float uAlpha;
varying vec3 vNormal;
varying vec3 vWorld;
varying float vDisp;
void main(){
  vec3 N=normalize(vNormal);
  vec3 V=normalize(uCam-vWorld);
  vec3 L=normalize(vec3(-0.45,0.75,0.6));
  float ndv=max(dot(N,V),0.0);
  float fres=pow(1.0-ndv,2.6);
  float diff=max(dot(N,L),0.0);
  float spec=pow(max(dot(reflect(-L,N),V),0.0),28.0);
  vec3 base=mix(uColorB,uColorA,smoothstep(-0.55,0.75,vDisp));
  float bands=smoothstep(0.86,1.0,sin(vDisp*13.0+uPhase*1.7))*0.5;
  float scan=smoothstep(0.985,1.0,sin(vWorld.y*38.0-uPhase*3.0))*0.18;
  vec3 col=base*(0.22+0.78*diff)+uColorA*(fres*1.7*uGlow+bands*uGlow+scan)+vec3(spec*0.75);
  float a=uAlpha*(0.82+0.18*fres);
  gl_FragColor=vec4(col*a,a);
}
"""

    /** Halo lumineux derrière la sphère (quad plein écran, mélange additif). */
    const val HALO_VS = """
attribute vec2 aPos;
varying vec2 vUv;
uniform float uAspect;
void main(){
  vUv=aPos*vec2(uAspect,1.0);
  gl_Position=vec4(aPos,0.0,1.0);
}
"""

    const val HALO_FS = """
precision mediump float;
varying vec2 vUv;
uniform vec3 uColor;
uniform float uTime;
uniform float uGlow;
uniform float uPulse;
uniform float uRadius;
void main(){
  float r=length(vUv)/uRadius;
  float glow=exp(-pow(max(r-0.7,0.0)*2.6,2.0))*0.8*uGlow;
  float inner=smoothstep(0.75,0.55,r)*0.0;
  float ringR=0.98+0.16*fract(uTime*0.55);
  float ring=exp(-pow((r-ringR)*28.0,2.0))*uPulse*(1.0-fract(uTime*0.55));
  float ring2=exp(-pow((r-1.22)*60.0,2.0))*0.22*uGlow;
  float a=clamp(glow+ring+ring2+inner,0.0,1.0);
  gl_FragColor=vec4(uColor*a,a*0.85);
}
"""

    /** Anneaux orbitaux en ruban avec traînée lumineuse. */
    const val RING_VS = """
uniform mat4 uMVP;
attribute vec3 aPos;
attribute float aAngle;
varying float vAngle;
void main(){
  vAngle=aAngle;
  gl_Position=uMVP*vec4(aPos,1.0);
}
"""

    const val RING_FS = """
precision mediump float;
uniform vec3 uColor;
uniform float uTime;
uniform float uSpeed;
uniform float uAlpha;
varying float vAngle;
void main(){
  float f=fract(vAngle/6.2831853-uTime*uSpeed);
  float trail=pow(f,3.0);
  float a=(0.12+0.88*trail)*uAlpha;
  gl_FragColor=vec4(uColor*a,a);
}
"""

    /** Particules flottantes autour de la sphère. */
    const val PART_VS = """
uniform mat4 uMVP;
uniform float uTime;
uniform float uSize;
uniform float uSpread;
attribute vec4 aSeed;
varying float vFade;
void main(){
  float ang=aSeed.x+uTime*(0.12+aSeed.w*0.25);
  float rad=1.25+aSeed.z*0.75*uSpread;
  float y=sin(aSeed.y+uTime*0.3*aSeed.w)*0.9;
  vec3 p=vec3(cos(ang)*rad*sqrt(1.0-y*y*0.5),y*rad*0.7,sin(ang)*rad*sqrt(1.0-y*y*0.5));
  vFade=0.35+0.65*fract(aSeed.w*7.0+uTime*0.2);
  gl_Position=uMVP*vec4(p,1.0);
  gl_PointSize=uSize*(0.6+aSeed.w)/gl_Position.w;
}
"""

    const val PART_FS = """
precision mediump float;
uniform vec3 uColor;
varying float vFade;
void main(){
  vec2 c=gl_PointCoord-vec2(0.5);
  float d=length(c);
  float a=smoothstep(0.5,0.0,d)*vFade*0.9;
  gl_FragColor=vec4(uColor*a,a);
}
"""
}
