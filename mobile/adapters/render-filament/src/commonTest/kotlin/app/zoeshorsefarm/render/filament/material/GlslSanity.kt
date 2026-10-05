package app.zoeshorsefarm.render.filament.material

import app.zoeshorsefarm.render.filament.mesh.VertexSemantic

/**
 * Checks that a generated material is consistent with itself, which is what can be verified without
 * a shader compiler: every parameter, variable and attribute that the code reads is declared, and
 * everything that is declared is used. Returns the problems found (empty: fine).
 */
internal object GlslSanity {
    private val builtInVertexFields = setOf("worldPosition", "worldNormal", "color", "uv0", "uv1")
    private val materialFunction = Regex("""void material\(inout MaterialInputs material\)""")
    private val vertexFunction = Regex("""void materialVertex\(inout MaterialVertexInputs material\)""")

    fun check(source: MaterialSource): List<String> =
        syntaxProblems(source) +
            parameterProblems(source) +
            variableProblems(source) +
            attributeProblems(source) +
            featureProblems(source)

    private fun syntaxProblems(source: MaterialSource): List<String> {
        val problems = ArrayList<String>()
        val fragment = source.fragment
        val vertex = source.vertex.orEmpty()
        for (text in listOf(fragment, vertex)) {
            if (text.count { it == '{' } != text.count { it == '}' }) problems += "unbalanced braces"
            if (text.count { it == '(' } != text.count { it == ')' }) problems += "unbalanced parentheses"
        }
        if (materialFunction.findAll(fragment).count() != 1) problems += "fragment needs exactly one material()"
        if (fragment.split("prepareMaterial(material);").size != 2) problems += "prepareMaterial must be called once"
        if (source.vertex != null && vertexFunction.findAll(vertex).count() != 1) {
            problems += "vertex needs exactly one materialVertex()"
        }
        val all = fragment + "\n" + vertex
        for (forbidden in listOf("gl_", "varying ", "uniform ", "attribute ")) {
            if (forbidden in all) problems += "three.js leftover: $forbidden"
        }
        return problems
    }

    private fun parameterProblems(source: MaterialSource): List<String> {
        val problems = ArrayList<String>()
        val all = source.fragment + "\n" + source.vertex.orEmpty()
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
        // the coat declares every uniform the horse code sets, also the ones one variant does not read
        val coatNames = (CoatGlsl.uniformColors + CoatGlsl.uniformScalars).toSet()
        (usedUniforms - uniforms).forEach { problems += "uniform $it is used but not declared" }
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
        return problems
    }

    private fun variableProblems(source: MaterialSource): List<String> {
        val problems = ArrayList<String>()
        val declared = source.variables.toSet()
        val read = Regex("""variable_(\w+)""").findAll(source.fragment).map { it.groupValues[1] }.toSet()
        val written =
            Regex("""material\.(\w+)\s*=""")
                .findAll(source.vertex.orEmpty())
                .map { it.groupValues[1] }
                .filter { it !in builtInVertexFields }
                .toSet()
        (read - declared).forEach { problems += "variable $it is read but not declared" }
        (written - declared).forEach { problems += "variable $it is written but not declared" }
        (declared - written).forEach { problems += "variable $it is never written" }
        val maxVariables = if (VertexSemantic.COLOR in source.requires) 4 else 5
        if (source.variables.size > maxVariables) problems += "too many variables"
        return problems
    }

    private fun attributeProblems(source: MaterialSource): List<String> {
        val problems = ArrayList<String>()
        val fragment = source.fragment
        val vertex = source.vertex.orEmpty()
        val usedCustom =
            Regex("""getCustom(\d)\(\)""")
                .findAll(vertex)
                .map { VertexSemantic.custom(it.groupValues[1].toInt()) }
                .toSet()
        val requiredCustom = source.requires.filter { it.ordinal >= VertexSemantic.CUSTOM0.ordinal }.toSet()
        (usedCustom - source.requires).forEach { problems += "$it is read but not required" }
        (requiredCustom - usedCustom).forEach { problems += "$it is required but not read" }
        val color = VertexSemantic.COLOR in source.requires
        if ("getColor()" in fragment && !color) problems += "getColor without COLOR"
        if ("material.color" in vertex && !color) problems += "material.color without COLOR"
        if (color && "getColor()" !in fragment) problems += "COLOR is required but not read"
        val uv = VertexSemantic.UV0 in source.requires
        if ("getUV0()" in fragment && !uv) problems += "getUV0 without UV0"
        if (uv && "getUV0()" !in fragment) problems += "UV0 is required but not read"
        return problems
    }

    private fun featureProblems(source: MaterialSource): List<String> {
        val problems = ArrayList<String>()
        val all = source.fragment + "\n" + source.vertex.orEmpty()
        val usesIndex = "getInstanceIndex()" in all
        if (usesIndex && !source.instanced) problems += "getInstanceIndex needs instanced"
        if (source.instanced && !usesIndex) problems += "instanced but the index is not read"
        if (source.customSurfaceShading != ("surfaceShading(" in source.fragment)) {
            problems += "custom shading and code disagree"
        }
        if (source.shading == SourceShading.UNLIT) {
            for (lit in listOf("material.roughness", "material.metallic", "material.reflectance", "material.normal")) {
                if (lit in source.fragment) problems += "unlit material sets $lit"
            }
            if ("worldNormal" in source.vertex.orEmpty()) problems += "unlit material touches the normal"
        }
        return problems
    }
}
