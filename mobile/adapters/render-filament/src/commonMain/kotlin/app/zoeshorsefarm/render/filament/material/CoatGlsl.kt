package app.zoeshorsefarm.render.filament.material

/**
 * Where the markings of the horse's head sit, in the head coordinates of the `aFace` attribute
 * (`MARKING_REGIONS` of `horse/coats.js`).
 */
data class MarkingRegions(
    val star: Star,
    val blaze: Blaze,
    val snip: Snip,
) {
    data class Star(
        val s: Float,
        val rs: Float,
        val ru: Float,
    )

    data class Blaze(
        val s0: Float,
        val s1: Float,
        val w0: Float,
        val w1: Float,
    )

    data class Snip(
        val s: Float,
        val rs: Float,
        val ru: Float,
    )

    companion object {
        val WEB =
            MarkingRegions(
                star = Star(s = 0.155f, rs = 0.048f, ru = 0.038f),
                blaze = Blaze(s0 = 0.08f, s1 = 0.615f, w0 = 0.026f, w1 = 0.042f),
                snip = Snip(s = 0.575f, rs = 0.026f, ru = 0.022f),
            )
    }
}

/**
 * The coat of the horse (`horse/material.js`): the colour is computed per pixel from the rest pose
 * position (`aRest`: noise for dapples and pinto patches, dark lower legs), material weights
 * (`aMat`: long hair, hoof, eye, inner ear) and head coordinates (`aFace`: along the head, lateral,
 * front-ness) for markings and nostrils. The three attributes arrive as custom attributes 0, 1 and
 * 2 and travel to the fragment shader as the variables `vRest`, `vMat` and `vFace`.
 *
 * The GLSL is the web code; only the way uniforms and varyings are named differs (Filament's
 * `materialParams.NAME` and `variable_NAME`). The coat is set by changing the uniform values of
 * the material instance, so changing the appearance never recompiles anything.
 */
internal object CoatGlsl {
    val uniformColors: List<String> =
        listOf("uBase", "uDark", "uBelly", "uHair", "uPointColor", "uMuzzle", "uHoof", "uWhite")
    val uniformScalars: List<String> = listOf("uPoints", "uDapple", "uPinto", "uMarking", "uFlare", "uBlink")

    val variables: List<String> = listOf("vRest", "vMat", "vFace")

    private val uniformPattern = Regex("\\b(" + (uniformColors + uniformScalars).joinToString("|") + ")\\b")

    /** The functions of the coat for the fragment block; `horseCoat()` returns the linear colour. */
    fun functions(
        low: Boolean,
        regions: MarkingRegions,
    ): String {
        val resolved = GlslPreprocessor.resolve(template(regions), if (low) setOf("HORSE_LOW") else emptySet())
        return uniformPattern.replace(resolved) { "materialParams.${it.value}" }
    }

    private fun template(regions: MarkingRegions): String {
        fun f(x: Float) = GlslNumber.format(x, decimals = MARKING_DECIMALS)
        val star = regions.star
        val blaze = regions.blaze
        val snip = regions.snip
        return """
$NOISE
float hzPinto(vec3 p, float head){
  float n = hzNoise(p * vec3(1.5, 1.25, 1.5) + vec3(3.1, 0.4, 1.7)) * 0.8
          + hzNoise(p * 4.2 + 7.0) * 0.16;
  float bias = (1.0 - smoothstep(0.32, 0.8, p.y)) * 0.95   // white legs
             + smoothstep(1.45, 1.7, p.y) * 0.25 * (1.0 - step(0.5, head)) // across the back
             - head * 1.2;                                // dark head
  return smoothstep(0.1, 0.17, n + bias);
}
vec3 horseCoat(){
  vec3 vRest = variable_vRest.xyz;
  vec4 vMat = variable_vMat;
  vec3 vFace = variable_vFace.xyz;
  vec3 p = vRest;
  float head = step(0.0, vFace.x);
  float legs = 1.0 - smoothstep(0.4, 0.56, p.y + 0.03 * hzNoise(p * 14.0));
#ifdef HORSE_LOW
  float fine = 0.0;
#else
  float fine = hzNoise(p * 3.0) * 0.6 + hzNoise(p * 11.0) * 0.4;
#endif
  vec3 col = uBase * (1.0 + 0.07 * fine);
  // darker topline, lighter belly/lower flank
  float body = step(0.75, p.y);
  col = mix(col, uDark, smoothstep(1.4, 1.75, p.y) * 0.55 * body * (1.0 - head));
  col = mix(col, uBelly, (1.0 - smoothstep(0.88, 1.12, p.y)) * body * 0.5);
  // dapple grey: light spots in a darker net, fine flecks
  if (uDapple > 0.5) {
    float d = hzNoise(p * 6.5);
    float d2 = hzNoise(p * 13.0 + 3.0);
    float net = smoothstep(0.0, 0.55, abs(d + 0.35 * d2));
    vec3 g = mix(uBase, uDark, (1.0 - net) * 0.5);
    g = mix(g, uBase * 1.04, smoothstep(1.2, 0.95, p.y) * 0.5);
#ifndef HORSE_LOW
    float sp = smoothstep(0.72, 0.85, hzNoise(p * 55.0));
    g = mix(g, uDark * 0.7, sp * 0.5);
#endif
    col = mix(g, uBase, head * 0.35);
  }
  // dark lower legs (bay: black up to carpus/hock)
  col = mix(col, uPointColor, legs * uPoints);
  // dark ear rims on the bay
  col = mix(col, uPointColor, smoothstep(2.18, 2.26, p.y) * uPoints * (1.0 - vMat.x));
  // long hair (mane, forelock, tail) with strands
  float strand = hzNoise(vec3(p.x * 70.0, p.y * 5.0, p.z * 70.0));
  col = mix(col, uHair * (1.0 + 0.22 * strand), vMat.x);
  // head: darker muzzle, markings, nostrils, mouth line
  if (head > 0.5) {
    float s = vFace.x, u = vFace.y, fr = vFace.z;
    float muz = smoothstep(0.49, 0.585, s);
    col = mix(col, uMuzzle, muz * 0.75);
    // darker brow
    col *= 1.0 - 0.18 * smoothstep(0.04, 0.0, abs(s - 0.15)) * smoothstep(0.1, 0.6, -fr + 0.5) * step(-0.2, fr);
    float wob = hzNoise(vec3(s * 28.0, u * 28.0, 3.0));
    float mark = 0.0;
    if (uMarking > 0.5 && uMarking < 1.5) {
      float d = length(vec2((s - ${f(star.s)}) / ${f(star.rs)}, u / ${f(star.ru)}));
      mark = (1.0 - smoothstep(0.8, 1.0, d + 0.18 * wob)) * smoothstep(0.35, 0.6, fr);
    } else if (uMarking > 1.5 && uMarking < 2.5) {
      float hw = mix(${f(blaze.w0)}, ${f(blaze.w1)}, smoothstep(0.3, 0.58, s)) + 0.008 * smoothstep(0.16, 0.08, s);
      float along = smoothstep(${f(
            blaze.s0,
        )}, ${f(blaze.s0 + 0.02f)}, s) * (1.0 - smoothstep(${f(blaze.s1 - 0.01f)}, ${f(blaze.s1 + 0.01f)}, s));
      mark = (1.0 - smoothstep(hw * 0.82, hw, abs(u) + 0.006 * wob)) * along * smoothstep(0.15, 0.35, fr);
    } else if (uMarking > 2.5) {
      float d = length(vec2((s - ${f(snip.s)}) / ${f(snip.rs)}, u / ${f(snip.ru)}));
      mark = (1.0 - smoothstep(0.75, 1.0, d + 0.15 * wob)) * smoothstep(0.2, 0.45, fr);
    }
    vec3 white = mix(uWhite, vec3(0.86, 0.66, 0.62), muz * 0.6);
    col = mix(col, white, mark);
    // nostrils (comma shape) and mouth line
    // the nostrils widen with the breathing (uFlare 0..1)
    float nz = length(vec2((s - 0.566 + (abs(u) - 0.048) * 0.5) / (0.02 + 0.006 * uFlare), (abs(u) - 0.047) / (0.0105 + 0.0045 * uFlare)));
    float nost = (1.0 - smoothstep(0.7, 1.0, nz)) * smoothstep(0.0, 0.25, fr) * (1.0 - smoothstep(0.75, 0.95, fr));
    col = mix(col, vec3(0.03, 0.02, 0.02), nost * 0.92);
    float mouth = smoothstep(0.03, 0.0, abs(fr + 0.4)) * smoothstep(0.54, 0.57, s);
    col = mix(col, col * 0.25, mouth * 0.85);
  }
  // pinto: large white patches (also in the long hair)
  if (uPinto > 0.5) {
    float wp = hzPinto(p, head);
    col = mix(col, uWhite * (1.0 + 0.05 * fine), wp * (1.0 - vMat.y));
  }
#ifdef HORSE_LOW
  // low: leg wraps are painted (no extra geometry): white fleece with a dark top edge on the cannon
  {
    float cannon = smoothstep(0.19, 0.215, p.y) * (1.0 - smoothstep(0.405, 0.43, p.y));
    cannon *= 1.0 - vMat.y;
    float trim = smoothstep(0.375, 0.395, p.y);
    vec3 wrapCol = mix(vec3(0.82, 0.82, 0.8), vec3(0.07, 0.1, 0.26), trim);
    col = mix(col, wrapCol, cannon * step(p.y, 0.45));
  }
#endif
  // hoof (light horn below a white leg), eye, inner ear
  vec3 hoof = uHoof * (1.0 + 0.25 * hzNoise(vec3(p.x * 90.0, p.y * 6.0, p.z * 90.0)));
  if (uPinto > 0.5) hoof = mix(hoof, vec3(0.55, 0.48, 0.38), hzPinto(p + vec3(0.0, 0.12, 0.0), 0.0));
  col = mix(col, hoof, vMat.y);
  float eyeOpen = vMat.z;
#ifdef HORSE_LOW
  // low: a blink paints the eye with the coat colour (medium and high have real eyelids)
  col = mix(col, uBase * 0.8, vMat.z * uBlink);
  eyeOpen = vMat.z * (1.0 - uBlink);
#endif
  col = mix(col, vec3(0.025, 0.017, 0.012), eyeOpen);
  col = mix(col, col * 0.35 + vec3(0.02), vMat.w);
  return col;
}
"""
    }

    private const val MARKING_DECIMALS = 4

    private const val NOISE = """
vec3 hz_mod289(vec3 x){return x-floor(x*(1.0/289.0))*289.0;}
vec4 hz_mod289(vec4 x){return x-floor(x*(1.0/289.0))*289.0;}
vec4 hz_perm(vec4 x){return hz_mod289(((x*34.0)+1.0)*x);}
vec4 hz_tis(vec4 r){return 1.79284291400159-0.85373472095314*r;}
float hzNoise(vec3 v){
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
  i=hz_mod289(i);
  vec4 p=hz_perm(hz_perm(hz_perm(i.z+vec4(0.0,i1.z,i2.z,1.0))+i.y+vec4(0.0,i1.y,i2.y,1.0))+i.x+vec4(0.0,i1.x,i2.x,1.0));
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
  vec4 norm=hz_tis(vec4(dot(p0,p0),dot(p1,p1),dot(p2,p2),dot(p3,p3)));
  p0*=norm.x;p1*=norm.y;p2*=norm.z;p3*=norm.w;
  vec4 m=max(0.6-vec4(dot(x0,x0),dot(x1,x1),dot(x2,x2),dot(x3,x3)),0.0);
  m=m*m;
  return 42.0*dot(m*m,vec4(dot(p0,x0),dot(p1,x1),dot(p2,x2),dot(p3,x3)));
}
"""
}
