package app.zoeshorsefarm.render.filament.material

import app.zoeshorsefarm.render.filament.mesh.VertexSemantic

/**
 * Checks that a generated material is consistent with itself, which is what can be verified without
 * a shader compiler: every parameter, variable and attribute that the code reads is declared, and
 * everything that is declared is used. Returns the problems found (empty: fine).
 */
internal object GlslSanity {
    private val builtInVertexFields = setOf("worldPosition", "worldNormal", "color", "uv0", "uv1")

    fun check(source: MaterialSource): List<String> {
        val problems = ArrayList<String>()
        val fragment = source.fragment
        val vertex = source.vertex.orEmpty()
        val all = fragment + "\n" + vertex

        for (text in listOf(fragment, vertex)) {
            if (text.count { it == '{' } != text.count { it == '}' }) problems += "unbalanced braces"
            if (text.count { it == '(' } != text.count { it == ')' }) problems += "unbalanced parentheses"
        }
        if (Regex("""void material\(inout MaterialInputs material\)""").findAll(fragment).count() != 1) {
            problems += "fragment needs exactly one material()"
        }
        if (fragment.split("prepareMaterial(material);").size != 2) problems += "prepareMaterial must be called once"
        if (source.vertex != null &&
            Regex("""void materialVertex\(inout MaterialVertexInputs material\)""").findAll(vertex).count() != 1
        ) {
            problems += "vertex needs exactly one materialVertex()"
        }
        for (forbidden in listOf("gl_", "varying ", "uniform ", "attribute ", "gl_InstanceID")) {
            if (forbidden in all) problems += "three.js leftover: $forbidden"
        }

        val uniforms =
            source.parameters
                .filterIsInstance<UniformParameter>()
                .map { it.name }
                .toSet()
        val samplers =
            source.parameters
                .filterIsInstance<SamplerParameter>()
                .map { it.name }
                .toSet()
        val usedUniforms = Regex("""materialParams\.(\w+)""").findAll(all).map { it.groupValues[1] }.toSet()
        val usedSamplers = Regex("""materialParams_(\w+)""").findAll(all).map { it.groupValues[1] }.toSet()
        (usedUniforms - uniforms).forEach { problems += "uniform $it is used but not declared" }
        // the coat declares every uniform the horse code sets, also the ones one variant does not read
        val coatNames = (CoatGlsl.uniformColors + CoatGlsl.uniformScalars).toSet()
        (uniforms - usedUniforms - coatNames).forEach { problems += "uniform $it is declared but not used" }
        (usedSamplers - samplers).forEach { problems += "sampler $it is used but not declared" }
        (samplers - usedSamplers).forEach { problems += "sampler $it is declared but not used" }
        if (source.parameters
                .map { it.name }
                .toSet()
                .size != source.parameters.size
        ) {
            problems += "duplicate parameter"
        }

        val readVariables = Regex("""variable_(\w+)""").findAll(fragment).map { it.groupValues[1] }.toSet()
        val writtenVariables =
            Regex("""material\.(\w+)\s*=""")
                .findAll(vertex)
                .map { it.groupValues[1] }
                .filter { it !in builtInVertexFields }
                .toSet()
        (readVariables - source.variables.toSet()).forEach { problems += "variable $it is read but not declared" }
        (writtenVariables - source.variables.toSet()).forEach { problems += "variable $it is written but not declared" }
        (source.variables.toSet() - writtenVariables).forEach { problems += "variable $it is never written" }
        val maxVariables = if (VertexSemantic.COLOR in source.requires) 4 else 5
        if (source.variables.size > maxVariables) problems += "too many variables"

        val usedCustom =
            Regex(
                """getCustom(\d)\(\)""",
            ).findAll(vertex).map { VertexSemantic.custom(it.groupValues[1].toInt()) }.toSet()
        (usedCustom - source.requires).forEach { problems += "$it is read but not required" }
        (source.requires.filter { it.ordinal >= VertexSemantic.CUSTOM0.ordinal }.toSet() - usedCustom)
            .forEach { problems += "$it is required but not read" }
        if ("getColor()" in fragment && VertexSemantic.COLOR !in source.requires) problems += "getColor without COLOR"
        if ("material.color" in vertex &&
            VertexSemantic.COLOR !in source.requires
        ) {
            problems += "material.color without COLOR"
        }
        if (VertexSemantic.COLOR in source.requires &&
            "getColor()" !in fragment
        ) {
            problems += "COLOR is required but not read"
        }
        if ("getUV0()" in fragment && VertexSemantic.UV0 !in source.requires) problems += "getUV0 without UV0"
        if (VertexSemantic.UV0 in source.requires && "getUV0()" !in fragment) problems += "UV0 is required but not read"

        if ("getInstanceIndex()" in all && !source.instanced) problems += "getInstanceIndex needs instanced"
        if (source.instanced && "getInstanceIndex()" !in all) problems += "instanced but the index is not read"
        if (source.customSurfaceShading !=
            ("surfaceShading(" in fragment)
        ) {
            problems += "custom shading and code disagree"
        }

        if (source.shading == SourceShading.UNLIT) {
            for (lit in listOf("material.roughness", "material.metallic", "material.reflectance", "material.normal")) {
                if (lit in fragment) problems += "unlit material sets $lit"
            }
            if ("worldNormal" in vertex) problems += "unlit material touches the normal"
        }
        return problems
    }
}
