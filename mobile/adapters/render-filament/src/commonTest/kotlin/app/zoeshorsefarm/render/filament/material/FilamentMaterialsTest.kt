package app.zoeshorsefarm.render.filament.material

import app.zoeshorsefarm.render.filament.mesh.MeshData
import app.zoeshorsefarm.render.filament.mesh.VertexSemantic
import io.github.erkko68.filament.UserVariantFilterBit
import kotlin.test.Test
import kotlin.test.assertEquals

/** The parts of the Filament bridge that do not need the native library. */
class FilamentMaterialsTest {
    @Test
    fun `an empty filter is the zero mask`() {
        assertEquals(0, FilamentMaterialCompiler.variantMask(emptySet()))
    }

    @Test
    fun `the filter maps onto the Filament variant bits`() {
        val mask =
            FilamentMaterialCompiler.variantMask(
                setOf(
                    FilteredVariant.DYNAMIC_LIGHTING,
                    FilteredVariant.VSM,
                    FilteredVariant.SSR,
                    FilteredVariant.STEREO,
                ),
            )
        assertEquals(
            UserVariantFilterBit.DYNAMIC_LIGHTING or UserVariantFilterBit.VSM or UserVariantFilterBit.SSR or
                UserVariantFilterBit.STE,
            mask,
        )
    }

    @Test
    fun `skinning is its own bit`() {
        assertEquals(
            UserVariantFilterBit.SKINNING,
            FilamentMaterialCompiler.variantMask(setOf(FilteredVariant.SKINNING)),
        )
    }

    @Test
    fun `directional lighting and shadow receiving are never filtered by the sources`() {
        val mask =
            FilamentMaterialCompiler.variantMask(
                MaterialSources.generate(MaterialSpec(Shading.LIT)).filteredVariants,
            )
        assertEquals(0, mask and UserVariantFilterBit.DIRECTIONAL_LIGHTING)
        assertEquals(0, mask and UserVariantFilterBit.SHADOW_RECEIVER)
        assertEquals(0, mask and UserVariantFilterBit.FOG)
    }

    @Test
    fun `a mesh that lacks vertex colours cannot use a vertex colour material`() {
        val mesh = MeshData(positions = FloatArray(9), normals = FloatArray(9))
        val missing = missingAttributes(MaterialSpec(Shading.LIT, vertexColors = true), mesh.semantics)
        assertEquals(listOf(VertexSemantic.COLOR), missing)
    }

    @Test
    fun `a mesh with everything a material needs has nothing missing`() {
        val mesh = MeshData(positions = FloatArray(9), normals = FloatArray(9), colors = FloatArray(9))
        assertEquals(emptyList(), missingAttributes(MaterialSpec(Shading.LIT, vertexColors = true), mesh.semantics))
    }
}
