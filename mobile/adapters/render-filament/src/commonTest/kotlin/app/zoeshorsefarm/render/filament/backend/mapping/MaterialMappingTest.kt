package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.material.Blend
import app.zoeshorsefarm.render.filament.material.CoatKind
import app.zoeshorsefarm.render.filament.material.InstancingMode
import app.zoeshorsefarm.render.filament.material.MaterialSpec
import app.zoeshorsefarm.render.filament.material.Shading
import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.UShortAttribute
import app.zoeshorsefarm.scene.graph.Bone
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.Points
import app.zoeshorsefarm.scene.graph.Skeleton
import app.zoeshorsefarm.scene.graph.SkinnedMesh
import app.zoeshorsefarm.scene.graph.Sprite
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.material.CoatEffect
import app.zoeshorsefarm.scene.material.CoatUniforms
import app.zoeshorsefarm.scene.material.LambertMaterial
import app.zoeshorsefarm.scene.material.PointsMaterial
import app.zoeshorsefarm.scene.material.SandEffect
import app.zoeshorsefarm.scene.material.ShaderMaterial
import app.zoeshorsefarm.scene.material.Side
import app.zoeshorsefarm.scene.material.SkyMaterial
import app.zoeshorsefarm.scene.material.SpriteMaterial
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.material.WindEffect
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.RgbaImage
import app.zoeshorsefarm.scene.texture.Texture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import app.zoeshorsefarm.render.filament.material.WindEffect as FilamentWind

class MaterialMappingTest {
    private val wind = Wind()

    private fun box(): Geometry = BoxGeometry()

    private fun withColors(geometry: Geometry): Geometry {
        geometry.setAttribute("color", FloatAttribute(FloatArray(geometry.vertexCount * 3), 3))
        return geometry
    }

    private fun mesh(
        material: StandardMaterial,
        geometry: Geometry = box(),
    ) = Mesh(geometry, material)

    private fun specOf(mesh: Mesh) = MaterialMapping.specFor(mesh, mesh.material, mesh.geometry)

    private fun texture() = Texture(RgbaImage(1, 1))

    @Test
    fun `a standard material is lit and a lambert material lambert`() {
        assertEquals(Shading.LIT, specOf(mesh(StandardMaterial()))?.shading)
        assertEquals(Shading.LAMBERT, specOf(Mesh(box(), LambertMaterial()))?.shading)
    }

    @Test
    fun `a basic material is unlit`() {
        assertEquals(Shading.UNLIT, specOf(Mesh(box(), BasicMaterial()))?.shading)
    }

    @Test
    fun `a plain opaque material gives the default spec`() {
        assertEquals(MaterialSpec(Shading.LIT), specOf(mesh(StandardMaterial())))
    }

    @Test
    fun `vertex colours count only when the geometry has a colour attribute`() {
        val material = StandardMaterial(vertexColors = true)
        assertEquals(false, specOf(mesh(material))?.vertexColors)
        assertEquals(true, specOf(mesh(material, withColors(box())))?.vertexColors)
    }

    @Test
    fun `a map or an alpha map needs uvs`() {
        val geometry = box()
        val material = StandardMaterial(map = texture(), alphaMap = texture())
        val spec = specOf(mesh(material, geometry))
        assertEquals(true, spec?.baseColorMap)
        assertEquals(true, spec?.alphaMap)
        geometry.deleteAttribute("uv")
        val bare = specOf(mesh(material, geometry))
        assertEquals(false, bare?.baseColorMap)
        assertEquals(false, bare?.alphaMap)
    }

    @Test
    fun `a normal map is used on lit plain meshes only`() {
        val material = StandardMaterial(normalMap = texture())
        assertEquals(true, specOf(mesh(material))?.normalMap)
        val instanced = InstancedMesh(box(), material, 3)
        assertEquals(false, MaterialMapping.specFor(instanced, material, instanced.geometry)?.normalMap)
    }

    @Test
    fun `transparent materials blend and keep their depth write setting`() {
        val glass = specOf(mesh(StandardMaterial(transparent = true)))
        assertEquals(Blend.TRANSPARENT, glass?.blend)
        // three.js writes depth by default, the spec's own default for blended materials is not to
        assertEquals(true, glass?.depthWrite)
        val decal = specOf(mesh(StandardMaterial(transparent = true, depthWrite = false)))
        assertNull(decal?.depthWrite)
        val solid = specOf(mesh(StandardMaterial(depthWrite = false)))
        assertEquals(false, solid?.depthWrite)
        assertNull(specOf(mesh(StandardMaterial()))?.depthWrite)
    }

    @Test
    fun `double sided and back side materials are drawn double sided`() {
        assertEquals(true, specOf(mesh(StandardMaterial(side = Side.DOUBLE)))?.doubleSided)
        assertEquals(true, specOf(mesh(StandardMaterial(side = Side.BACK)))?.doubleSided)
        assertEquals(false, specOf(mesh(StandardMaterial()))?.doubleSided)
    }

    @Test
    fun `tone mapping off is carried into the spec`() {
        assertEquals(false, specOf(Mesh(box(), BasicMaterial(toneMapped = false)))?.toneMapped)
    }

    @Test
    fun `instanced meshes use the instance data and colours when they have them`() {
        val material = StandardMaterial()
        val plain = InstancedMesh(box(), material, 4)
        assertEquals(InstancingMode.TRANSFORMS, MaterialMapping.specFor(plain, material, plain.geometry)?.instancing)
        val coloured = InstancedMesh(box(), material, 4)
        coloured.setColorAt(0, Color(0xff0000))
        assertEquals(
            InstancingMode.TRANSFORMS_AND_COLOR,
            MaterialMapping.specFor(coloured, material, coloured.geometry)?.instancing,
        )
    }

    @Test
    fun `tree wind on an instanced mesh becomes the tree effect`() {
        val material = StandardMaterial(effect = WindEffect.tree(wind))
        val trees = InstancedMesh(box(), material, 2)
        assertEquals(FilamentWind.Tree, MaterialMapping.specFor(trees, material, trees.geometry)?.wind)
    }

    @Test
    fun `bush tuft and wings effects map one to one`() {
        val kinds =
            listOf(
                WindEffect.bush(wind) to FilamentWind.Bush,
                WindEffect.tuft(wind) to FilamentWind.Tuft,
                WindEffect.wings(wind, 12.0, 0.4, 0.25) to FilamentWind.Wings(12f, 0.4f, 0.25f),
            )
        for ((effect, expected) in kinds) {
            val material = LambertMaterial(effect = effect)
            val mesh = InstancedMesh(box(), material, 2)
            assertEquals(expected, MaterialMapping.specFor(mesh, material, mesh.geometry)?.wind)
        }
    }

    @Test
    fun `wind that needs instances is dropped on a plain mesh`() {
        val material = StandardMaterial(effect = WindEffect.tree(wind))
        assertEquals(FilamentWind.None, specOf(mesh(material))?.wind)
    }

    @Test
    fun `an effect that is switched off does not reach the spec`() {
        val material = StandardMaterial(effect = WindEffect.tree(wind))
        material.effectEnabled = false
        val trees = InstancedMesh(box(), material, 2)
        assertEquals(FilamentWind.None, MaterialMapping.specFor(trees, material, trees.geometry)?.wind)
    }

    @Test
    fun `blossom wind needs instance colours vertex colours and the petal attribute`() {
        val material = LambertMaterial(vertexColors = true, effect = WindEffect.blossoms(wind, base = 0.5))
        val geometry = withColors(box())
        val flowers = InstancedMesh(geometry, material, 2)
        flowers.setColorAt(0, Color(0xffffff))
        assertEquals(FilamentWind.None, MaterialMapping.specFor(flowers, material, geometry)?.wind)
        geometry.setAttribute("petal", FloatAttribute(FloatArray(geometry.vertexCount), 1))
        assertEquals(FilamentWind.Blossom(0.5f), MaterialMapping.specFor(flowers, material, geometry)?.wind)
        val uncoloured = InstancedMesh(geometry, material, 2)
        assertEquals(FilamentWind.None, MaterialMapping.specFor(uncoloured, material, geometry)?.wind)
    }

    @Test
    fun `bunting wind needs the flutter attribute and no instancing`() {
        val material = LambertMaterial(vertexColors = true, side = Side.DOUBLE, effect = WindEffect.bunting(wind))
        val geometry = withColors(box())
        assertEquals(FilamentWind.None, specOf(Mesh(geometry, material))?.wind)
        geometry.setAttribute("aFlutter", FloatAttribute(FloatArray(geometry.vertexCount * 3), 3))
        assertEquals(FilamentWind.Bunting, specOf(Mesh(geometry, material))?.wind)
    }

    private fun coatGeometry(): Geometry {
        val geometry = box()
        val n = geometry.vertexCount
        geometry.setAttribute("aRest", FloatAttribute(FloatArray(n * 3), 3))
        geometry.setAttribute("aMat", FloatAttribute(FloatArray(n * 4), 4))
        geometry.setAttribute("aFace", FloatAttribute(FloatArray(n * 3), 3))
        return geometry
    }

    @Test
    fun `the coat effect needs its three attributes and picks the low variant`() {
        val standard = StandardMaterial(effect = CoatEffect(CoatUniforms(), low = false))
        val low = LambertMaterial(effect = CoatEffect(CoatUniforms(), low = true))
        assertNull(specOf(mesh(standard))?.coat)
        assertEquals(CoatKind.STANDARD, specOf(mesh(standard, coatGeometry()))?.coat)
        assertEquals(CoatKind.LOW, specOf(Mesh(coatGeometry(), low))?.coat)
    }

    @Test
    fun `the coat paints the whole surface so maps and vertex colours are left out`() {
        val material =
            StandardMaterial(vertexColors = true, map = texture(), effect = CoatEffect(CoatUniforms(), false))
        val spec = specOf(mesh(material, withColors(coatGeometry())))
        assertEquals(CoatKind.STANDARD, spec?.coat)
        assertEquals(false, spec?.vertexColors)
        assertEquals(false, spec?.baseColorMap)
    }

    private fun skinnedMesh(bones: Int): SkinnedMesh {
        val geometry = box()
        val n = geometry.vertexCount
        geometry.setAttribute("skinIndex", UShortAttribute(ShortArray(n * 4), 4))
        geometry.setAttribute("skinWeight", FloatAttribute(FloatArray(n * 4), 4))
        val mesh = SkinnedMesh(geometry, StandardMaterial())
        if (bones > 0) mesh.bind(Skeleton(List(bones) { Bone() }))
        return mesh
    }

    @Test
    fun `a skinned mesh with a skeleton and skin attributes is skinned`() {
        val mesh = skinnedMesh(3)
        assertEquals(true, specOf(mesh)?.skinning)
        assertEquals(3, MaterialMapping.boneCountOf(mesh))
    }

    @Test
    fun `a skinned mesh without a skeleton or with too many bones is drawn unskinned`() {
        assertEquals(false, specOf(skinnedMesh(0))?.skinning)
        val many = skinnedMesh(300)
        assertEquals(false, specOf(many)?.skinning)
        assertEquals(0, MaterialMapping.boneCountOf(many))
    }

    @Test
    fun `a skinned mesh drops wind and instancing`() {
        val mesh = skinnedMesh(2)
        mesh.material = StandardMaterial(effect = WindEffect.tree(wind))
        val spec = specOf(mesh)
        assertEquals(FilamentWind.None, spec?.wind)
        assertEquals(InstancingMode.NONE, spec?.instancing)
    }

    @Test
    fun `the sky material and the sky shader material are the sky shading`() {
        val sky = SkyMaterial(0x3f7fcf, 0xcfe2ee, 0xb8c7bf, 0xfff1d6, Vec3(0.0, 1.0, 0.0))
        assertEquals(Shading.SKY, specOf(Mesh(box(), sky))?.shading)
        val named = ShaderMaterial("sky", emptyMap())
        assertEquals(Shading.SKY, specOf(Mesh(box(), named))?.shading)
    }

    @Test
    fun `an unknown shader program has no spec`() {
        assertNull(specOf(Mesh(box(), ShaderMaterial("water", emptyMap()))))
    }

    @Test
    fun `points with the hoof dust shader or a points material become sprites`() {
        val dust = ShaderMaterial("hoof-dust", mapOf(), transparent = true, depthWrite = false)
        val points = Points(Geometry(), dust)
        val spec = MaterialMapping.specFor(points, dust, null)
        assertEquals(Shading.SPRITE, spec?.shading)
        assertEquals(Blend.TRANSPARENT, spec?.blend)
        assertNull(spec?.depthWrite)
        val plain = PointsMaterial()
        assertEquals(Shading.SPRITE, MaterialMapping.specFor(Points(Geometry(), plain), plain, null)?.shading)
    }

    @Test
    fun `a points object with a mesh material has no spec`() {
        val material = BasicMaterial()
        assertNull(MaterialMapping.specFor(Points(Geometry(), material), material, null))
    }

    @Test
    fun `a sprite is unlit transparent and double sided with its map`() {
        val material = SpriteMaterial(map = texture(), depthWrite = false)
        val spec = MaterialMapping.specFor(Sprite(material), material, null)
        assertEquals(Shading.UNLIT, spec?.shading)
        assertEquals(Blend.TRANSPARENT, spec?.blend)
        assertEquals(true, spec?.doubleSided)
        assertEquals(true, spec?.baseColorMap)
    }

    @Test
    fun `the sand effect becomes the sand variant on standard and lambert materials`() {
        val standard = StandardMaterial(vertexColors = true, effect = SandEffect(20.0, 35.0))
        val lambert = LambertMaterial(effect = SandEffect(20.0, 35.0))
        assertEquals(true, specOf(mesh(standard, withColors(box())))?.sand)
        assertEquals(true, specOf(Mesh(box(), lambert))?.sand)
        standard.effectEnabled = false
        assertEquals(false, specOf(mesh(standard, withColors(box())))?.sand)
    }

    @Test
    fun `the sand is dropped on instanced meshes`() {
        val material = StandardMaterial(effect = SandEffect(20.0, 35.0))
        val instanced = InstancedMesh(box(), material, 2)
        assertEquals(false, MaterialMapping.specFor(instanced, material, instanced.geometry)?.sand)
    }

    @Test
    fun `equal usage gives equal specs`() {
        val material = StandardMaterial(roughness = 0.2)
        assertEquals(specOf(mesh(material)), specOf(mesh(StandardMaterial(roughness = 0.9))))
        assertNotNull(specOf(mesh(material)))
    }

    @Test
    fun `windOfMaterial finds the wind of the active effect only`() {
        val material = StandardMaterial(effect = WindEffect.tree(wind))
        assertSame(wind, MaterialMapping.windOfMaterial(material))
        material.effectEnabled = false
        assertNull(MaterialMapping.windOfMaterial(material))
        assertNull(MaterialMapping.windOfMaterial(StandardMaterial()))
    }
}
