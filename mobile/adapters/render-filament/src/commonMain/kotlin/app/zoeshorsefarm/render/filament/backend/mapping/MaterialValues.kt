package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.material.MaterialSources
import app.zoeshorsefarm.render.filament.material.MaterialSpec
import app.zoeshorsefarm.render.filament.material.Shading
import app.zoeshorsefarm.scene.material.CoatEffect
import app.zoeshorsefarm.scene.material.CoatUniforms
import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.material.PointsMaterial
import app.zoeshorsefarm.scene.material.SandEffect
import app.zoeshorsefarm.scene.material.ShaderMaterial
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Vec3

/** Where the values of a material go: the uniforms of a Filament material instance. */
interface ParamSink {
    fun setFloat(
        name: String,
        x: Float,
    )

    fun setFloat3(
        name: String,
        x: Float,
        y: Float,
        z: Float,
    )

    fun setFloat4(
        name: String,
        x: Float,
        y: Float,
        z: Float,
        w: Float,
    )
}

/**
 * Writes the uniform values of a scene material into a [ParamSink], by the names the generated
 * shaders declare (see `MaterialSources`). Colours are the linear working colours of the scene model,
 * which is what Filament expects. Nothing is allocated, so the binding can call it every frame and
 * pass only what changed on to Filament.
 */
object MaterialValues {
    private val SKY_COLORS = arrayOf("zenith", "horizon", "groundColor", "sunColor")

    private val COAT_COLORS: List<Pair<String, (CoatUniforms) -> Color>> =
        listOf(
            "uBase" to { u -> u.base },
            "uDark" to { u -> u.dark },
            "uBelly" to { u -> u.belly },
            "uHair" to { u -> u.hair },
            "uPointColor" to { u -> u.pointColor },
            "uMuzzle" to { u -> u.muzzle },
            "uHoof" to { u -> u.hoof },
            "uWhite" to { u -> u.white },
        )

    fun write(
        material: Material,
        spec: MaterialSpec,
        out: ParamSink,
    ) {
        when (spec.shading) {
            Shading.SKY -> writeSky(material, out)
            Shading.SPRITE -> writeSprite(material, out)
            else -> writeSurface(material, spec, out)
        }
    }

    private fun writeSurface(
        material: Material,
        spec: MaterialSpec,
        out: ParamSink,
    ) {
        val coat = material.activeEffect as? CoatEffect
        if (spec.coat != null && coat != null) {
            writeCoat(coat.uniforms, out)
        } else {
            val c = material.color
            out.setFloat4(
                MaterialSources.BASE_COLOR,
                c.r.toFloat(),
                c.g.toFloat(),
                c.b.toFloat(),
                material.opacity.toFloat(),
            )
        }
        val sand = material.activeEffect as? SandEffect
        if (spec.sand && sand != null) {
            out.setFloat3(MaterialSources.ARENA_HALF, sand.arenaHalfWidth.toFloat(), sand.arenaHalfLength.toFloat(), 0f)
        }
        val standard = material as? StandardMaterial ?: return
        if (spec.shading == Shading.LIT) {
            out.setFloat(MaterialSources.ROUGHNESS, standard.roughness.toFloat())
            if (spec.coat == null) out.setFloat(MaterialSources.METALLIC, standard.metalness.toFloat())
        }
        if (spec.normalMap) out.setFloat(MaterialSources.NORMAL_SCALE, standard.normalScale.x.toFloat())
    }

    private fun writeCoat(
        uniforms: CoatUniforms,
        out: ParamSink,
    ) {
        for (i in COAT_COLORS.indices) {
            val entry = COAT_COLORS[i]
            val c = entry.second(uniforms)
            out.setFloat3(entry.first, c.r.toFloat(), c.g.toFloat(), c.b.toFloat())
        }
        out.setFloat("uPoints", uniforms.points.toFloat())
        out.setFloat("uDapple", uniforms.dapple.toFloat())
        out.setFloat("uPinto", uniforms.pinto.toFloat())
        out.setFloat("uMarking", uniforms.marking.toFloat())
        out.setFloat("uFlare", uniforms.flare.toFloat())
        out.setFloat("uBlink", uniforms.blink.toFloat())
    }

    /** The sky takes its five uniforms by name, from a `SkyMaterial` or any `ShaderMaterial("sky")`. */
    private fun writeSky(
        material: Material,
        out: ParamSink,
    ) {
        val uniforms = (material as? ShaderMaterial)?.uniforms ?: return
        for (i in SKY_COLORS.indices) {
            val name = SKY_COLORS[i]
            val color = uniforms[name]?.value as? Color
            if (color != null) out.setFloat3(name, color.r.toFloat(), color.g.toFloat(), color.b.toFloat())
        }
        val direction = uniforms["sunDir"]?.value as? Vec3 ?: return
        out.setFloat3("sunDir", direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
    }

    /** The dust colour (`uColor` of the web shader) or the colour and opacity of a `PointsMaterial`. */
    private fun writeSprite(
        material: Material,
        out: ParamSink,
    ) {
        val dust = (material as? ShaderMaterial)?.uniforms?.get("uColor")?.value as? Color
        val color = dust ?: material.color
        val opacity = if (dust != null || material !is PointsMaterial) 1.0 else material.opacity
        out.setFloat4(
            MaterialSources.BASE_COLOR,
            color.r.toFloat(),
            color.g.toFloat(),
            color.b.toFloat(),
            opacity.toFloat(),
        )
    }
}
