package net.okitsu.ysmepicfightcompat.geometry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockGeometryParserTest {
    private static final String ALL_FACE_UVS = """
            {"west":{"uv":[0,0],"uv_size":[8,8]},
             "east":{"uv":[8,0],"uv_size":[8,8]},
             "north":{"uv":[16,0],"uv_size":[8,8]},
             "south":{"uv":[24,0],"uv_size":[8,8]},
             "up":{"uv":[32,0],"uv_size":[8,8]},
             "down":{"uv":[40,0],"uv_size":[8,8]}}
            """;

    @ParameterizedTest
    @CsvSource({"0,false", "0,true", "1,false", "1,true", "2,false", "2,true"})
    void culledFlatCubesKeepBothAuthoredSidesAndTheirUvs(int axis, boolean differentUvs) {
        String[] firstSide = {"west", "up", "north"};
        String[] secondSide = {"east", "down", "south"};
        String[] sizes = {"[0,8,8]", "[8,0,8]", "[8,8,0]"};
        int secondU = differentUvs ? 32 : 8;
        String uv = """
                {"%s":{"uv":[8,16],"uv_size":[8,8]},
                 "%s":{"uv":[%d,16],"uv_size":[8,8]}}
                """.formatted(firstSide[axis], secondSide[axis], secondU);
        for (String boneName : List.of("plane", "ysmGlow_plane")) {
            String source = cube(boneName, sizes[axis], uv, "", "");
            List<GeometryDocument.Face> faces = faces(source, boneName, true);
            assertOppositeFaces(faces);
            assertEquals(16.0F / 64.0F, faces.get(0).textureCoordinates()[0][0]);
            assertEquals((secondU + 8.0F) / 64.0F, faces.get(1).textureCoordinates()[0][0]);
            assertEquals(16.0F / 64.0F, faces.get(0).textureCoordinates()[0][1]);
            assertEquals(16.0F / 64.0F, faces.get(1).textureCoordinates()[0][1]);

            GeometryDocument.Face defaultFace = BedrockGeometryParser.parse(source)
                    .bones().get(boneName).faces().get(0);
            List<GeometryDocument.Face> nonCulled = faces(source, boneName, false);
            assertEquals(1, nonCulled.size());
            assertArrayEquals(defaultFace.positions(), nonCulled.get(0).positions());
            assertEquals(defaultFace.normal(), nonCulled.get(0).normal());
            for (int corner = 0; corner < 4; corner++) {
                assertArrayEquals(defaultFace.textureCoordinates()[corner],
                        nonCulled.get(0).textureCoordinates()[corner]);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"south\":{\"uv\":[24,0],\"uv_size\":[0,8]},",
            "\"south\":{\"uv\":[24,0],\"uv_size\":[8,0]},",
            "\"south\":{\"uv\":[24,0]},",
            ""})
    void cullingDoesNotRestoreDisabledMissingOrZeroAreaFaces(String oppositeUv) {
        String uv = "{" + oppositeUv + """
                "north":{"uv":[16,0],"uv_size":[8,8]},
                "west":{"uv":[0,0],"uv_size":[8,8]},
                "east":{"uv":[8,0],"uv_size":[8,8]},
                "up":{"uv":[32,0],"uv_size":[8,8]},
                "down":{"uv":[40,0],"uv_size":[8,8]}}
                """;
        for (boolean allCutout : new boolean[]{false, true}) {
            List<GeometryDocument.Face> faces = faces(
                    cube("plane", "[8,8,0]", uv, "", ""), "plane", allCutout);
            assertEquals(1, faces.size());
            assertEquals(-1.0F, faces.get(0).normal().z());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"[8,0,0]", "[0,8,0]", "[0,0,8]", "[0,0,0]"})
    void cullingDoesNotCreateFacesForLinesOrPoints(String size) {
        for (boolean allCutout : new boolean[]{false, true}) {
            assertTrue(faces(cube("plane", size, ALL_FACE_UVS, "", ""),
                    "plane", allCutout).isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void culledMirroredAndNegativeSizedPlanesKeepOppositeWindings(
            boolean boneMirror, boolean cubeMirror) {
        for (String size : List.of("[0,8,8]", "[8,0,8]", "[8,8,0]",
                "[0,-8,8]", "[-8,0,8]", "[-8,8,0]")) {
            List<GeometryDocument.Face> faces = faces(cube("plane", size, ALL_FACE_UVS,
                    ",\"mirror\":" + boneMirror, ",\"mirror\":" + cubeMirror), "plane", true);
            assertOppositeFaces(faces);
        }
    }

    @Test
    void culledPlanesRetainNegativeUvExtents() {
        String uv = """
                {"north":{"uv":[16,16],"uv_size":[-8,8]},
                 "south":{"uv":[32,16],"uv_size":[8,-8]}}
                """;
        List<GeometryDocument.Face> faces = faces(
                cube("plane", "[8,8,0]", uv, "", ""), "plane", true);
        assertOppositeFaces(faces);
        assertEquals(8.0F / 64.0F, faces.get(0).textureCoordinates()[0][0]);
        assertEquals(8.0F / 64.0F, faces.get(1).textureCoordinates()[2][1]);
    }

    @Test
    void flatnessUsesInflatedExtentsAndCubeInflateOverridesTheBone() {
        String expanded = cube("plane", "[8,8,0]", ALL_FACE_UVS, "", ",\"inflate\":1");
        String collapsed = cube("plane", "[8,8,2]", ALL_FACE_UVS, "", ",\"inflate\":-1");
        String inherited = cube("plane", "[8,8,2]", ALL_FACE_UVS, ",\"inflate\":-1", "");
        String overridden = cube("plane", "[8,8,2]", ALL_FACE_UVS,
                ",\"inflate\":-1", ",\"inflate\":0");
        String thin = cube("plane", "[8,8,0.0001]", ALL_FACE_UVS, "", "");
        for (boolean allCutout : new boolean[]{false, true}) {
            assertEquals(6, faces(expanded, "plane", allCutout).size());
            assertEquals(allCutout ? 2 : 1, faces(collapsed, "plane", allCutout).size());
            assertEquals(allCutout ? 2 : 1, faces(inherited, "plane", allCutout).size());
            assertEquals(6, faces(overridden, "plane", allCutout).size());
            assertEquals(6, faces(thin, "plane", allCutout).size());
        }
        assertOppositeFaces(faces(collapsed, "plane", true));
    }

    @Test
    void subpixelProbeCubesKeepAllFacesWithExplicitInteriorPaletteUvs() {
        for (String size : new String[]{"[3,0.7,0.5]", "[0.2,7,0.2]", "[7,0.2,0.2]"}) {
            GeometryDocument geometry = BedrockGeometryParser.parse("""
                    {"minecraft:geometry":[{
                      "description":{"texture_width":128,"texture_height":128},
                      "bones":[{"name":"pointer","cubes":[{
                        "origin":[0,0,0],"size":%s,
                        "uv":{
                          "north":{"uv":[72,40],"uv_size":[1,1]},
                          "south":{"uv":[72,40],"uv_size":[1,1]},
                          "east":{"uv":[72,40],"uv_size":[1,1]},
                          "west":{"uv":[72,40],"uv_size":[1,1]},
                          "up":{"uv":[72,40],"uv_size":[1,1]},
                          "down":{"uv":[72,40],"uv_size":[1,1]}
                        }
                      }]}]
                    }]}
                    """.formatted(size));
            assertNotNull(geometry);
            var faces = geometry.bones().get("pointer").faces();
            assertEquals(6, faces.size(), size);
            for (var face : faces) {
                for (float[] coordinate : face.textureCoordinates()) {
                    assertTrue(coordinate[0] >= 72.0F / 128 && coordinate[0] <= 73.0F / 128);
                    assertTrue(coordinate[1] >= 40.0F / 128 && coordinate[1] <= 41.0F / 128);
                }
            }
        }
    }

    @Test
    void buildsTheBoneTreeAndAllSixCubeFaces() {
        GeometryDocument geometry = BedrockGeometryParser.parse("""
                {"minecraft:geometry":[{
                  "description":{"texture_width":32,"texture_height":64},
                  "bones":[
                    {"name":"root","pivot":[0,0,0]},
                    {"name":"tail","parent":"root","pivot":[0,12,0],
                     "cubes":[{"origin":[-4,0,-2],"size":[8,12,4],"uv":[0,0]}]}
                  ]
                }]}
                """);

        assertNotNull(geometry);
        assertEquals(32, geometry.textureWidth());
        assertEquals(64, geometry.textureHeight());
        assertEquals(1, geometry.roots().size());
        assertSame(geometry.bones().get("root"), geometry.bones().get("tail").parent());
        assertEquals(6, geometry.bones().get("tail").faces().size());
    }

    @Test
    void mirroredBoxKeepsTopAndBottomUvsOnTheirOwnFaces() {
        GeometryDocument geometry = BedrockGeometryParser.parse("""
                {"minecraft:geometry":[{
                  "description":{"texture_width":64,"texture_height":64},
                  "bones":[
                    {"name":"left_hand","cubes":[
                      {"origin":[0,0,0],"size":[1,1,1],"uv":[10,20]}]},
                    {"name":"right_hand","cubes":[
                      {"origin":[0,0,0],"size":[1,1,1],"uv":[10,20],"mirror":true}]}
                  ]
                }]}
                """);

        GeometryDocument.Face normalDown = geometry.bones().get("left_hand").faces().get(5);
        GeometryDocument.Face mirroredUp = geometry.bones().get("right_hand").faces().get(4);
        GeometryDocument.Face mirroredDown = geometry.bones().get("right_hand").faces().get(5);

        assertTrue(Arrays.stream(mirroredUp.positions()).allMatch(vertex -> vertex.y() == 1.0F / 16.0F));
        assertTrue(Arrays.stream(mirroredDown.positions()).allMatch(vertex -> vertex.y() == 0.0F));
        assertEquals(13.0F / 64.0F, normalDown.textureCoordinates()[0][0]);
        assertEquals(21.0F / 64.0F, normalDown.textureCoordinates()[0][1]);
        assertEquals(11.0F / 64.0F, mirroredUp.textureCoordinates()[0][0]);
        assertEquals(20.0F / 64.0F, mirroredUp.textureCoordinates()[0][1]);
        assertEquals(12.0F / 64.0F, mirroredDown.textureCoordinates()[0][0]);
        assertEquals(21.0F / 64.0F, mirroredDown.textureCoordinates()[0][1]);
    }

    @Test
    void perFaceUvsRemainAttachedToNamedTailFaces() {
        GeometryDocument geometry = BedrockGeometryParser.parse("""
                {"minecraft:geometry":[{
                  "description":{"texture_width":128,"texture_height":64},
                  "bones":[{"name":"tail_tip","cubes":[{
                    "origin":[0,0,0],"size":[2,3,4],
                    "uv":{
                      "north":{"uv":[40,8],"uv_size":[2,3]},
                      "south":{"uv":[52,12],"uv_size":[2,3]},
                      "up":{"uv":[60,4],"uv_size":[2,4]}
                    }
                  }]}]
                }]}
                """);

        var faces = geometry.bones().get("tail_tip").faces();
        assertEquals(3, faces.size());
        assertEquals(42.0F / 128.0F, faces.get(0).textureCoordinates()[0][0]);
        assertEquals(8.0F / 64.0F, faces.get(0).textureCoordinates()[0][1]);
        assertEquals(54.0F / 128.0F, faces.get(1).textureCoordinates()[0][0]);
        assertEquals(12.0F / 64.0F, faces.get(1).textureCoordinates()[0][1]);
        assertEquals(62.0F / 128.0F, faces.get(2).textureCoordinates()[0][0]);
        assertEquals(4.0F / 64.0F, faces.get(2).textureCoordinates()[0][1]);
    }

    @Test
    void zeroSizedUvsDisableCoplanarFacesInsteadOfCreatingZFightGeometry() {
        GeometryDocument geometry = BedrockGeometryParser.parse("""
                {"minecraft:geometry":[{
                  "description":{"texture_width":256,"texture_height":256},
                  "bones":[{"name":"ysmGlow_magic_circle","cubes":[{
                    "origin":[0,0,0],"size":[27,27,0],
                    "uv":{
                      "north":{"uv":[161,50],"uv_size":[45,45]},
                      "east":{"uv":[0,0],"uv_size":[0,30]},
                      "south":{"uv":[256,0],"uv_size":[0,0]},
                      "west":{"uv":[0,0],"uv_size":[0,30]},
                      "up":{"uv":[30,0],"uv_size":[-30,0]},
                      "down":{"uv":[30,0],"uv_size":[-30,0]}
                    }
                  }]}]
                }]}
                """);

        var faces = geometry.bones().get("ysmGlow_magic_circle").faces();
        assertEquals(1, faces.size());
        assertEquals(206.0F / 256.0F, faces.get(0).textureCoordinates()[0][0]);
        assertEquals(50.0F / 256.0F, faces.get(0).textureCoordinates()[0][1]);
    }

    @Test
    void populatedOppositeUvsStillProduceOnlyOneZeroThicknessGlowPlane() {
        GeometryDocument geometry = BedrockGeometryParser.parse("""
                {"minecraft:geometry":[{
                  "description":{"texture_width":256,"texture_height":256},
                  "bones":[{"name":"ysmGlow_magic_circle","cubes":[{
                    "origin":[0,0,0],"size":[27,27,0],
                    "uv":{
                      "north":{"uv":[161,50],"uv_size":[45,45]},
                      "south":{"uv":[161,50],"uv_size":[45,45]}
                    }
                  }]}]
                }]}
                """);

        var faces = geometry.bones().get("ysmGlow_magic_circle").faces();
        assertEquals(1, faces.size());
        assertEquals(-1.0F, faces.get(0).normal().z());
    }

    @Test
    void zeroAreaEdgeFacesAreDiscardedEvenWhenTheirUvsArePopulated() {
        GeometryDocument geometry = BedrockGeometryParser.parse("""
                {"minecraft:geometry":[{
                  "description":{"texture_width":64,"texture_height":64},
                  "bones":[{"name":"flat","cubes":[{
                    "origin":[0,0,0],"size":[8,8,0],
                    "uv":{
                      "north":{"uv":[0,0],"uv_size":[8,8]},
                      "east":{"uv":[8,0],"uv_size":[2,8]},
                      "west":{"uv":[10,0],"uv_size":[2,8]},
                      "up":{"uv":[12,0],"uv_size":[8,2]},
                      "down":{"uv":[20,0],"uv_size":[8,2]}
                    }
                  }]}]
                }]}
                """);

        assertEquals(1, geometry.bones().get("flat").faces().size());
    }

    private static String cube(String name, String size, String uv,
                               String boneSettings, String cubeSettings) {
        return """
                {"minecraft:geometry":[{
                  "description":{"texture_width":64,"texture_height":64},
                  "bones":[{"name":"%s"%s,"cubes":[{
                    "origin":[0,0,0],"size":%s,"uv":%s%s
                  }]}]
                }]}
                """.formatted(name, boneSettings, size, uv, cubeSettings);
    }

    private static List<GeometryDocument.Face> faces(String source, String name, boolean allCutout) {
        GeometryDocument geometry = BedrockGeometryParser.parse(source, allCutout);
        assertNotNull(geometry);
        return geometry.bones().get(name).faces();
    }

    private static void assertOppositeFaces(List<GeometryDocument.Face> faces) {
        assertEquals(2, faces.size());
        GeometryDocument.Face first = faces.get(0);
        GeometryDocument.Face second = faces.get(1);
        for (Vector3f position : first.positions()) {
            // Opposite extents can represent the same coordinate as -0.0 and +0.0.
            assertTrue(Arrays.stream(second.positions()).anyMatch(candidate ->
                    candidate.distanceSquared(position) <= 1.0E-12F));
        }
        assertEquals(-1.0F, first.normal().dot(second.normal()), 0.00001F);
        assertTrue(winding(first).dot(winding(second)) < 0.0F,
                "Both original triangle windings must remain available to back-face culling");
    }

    private static Vector3f winding(GeometryDocument.Face face) {
        return new Vector3f(face.positions()[1]).sub(face.positions()[0])
                .cross(new Vector3f(face.positions()[2]).sub(face.positions()[0]));
    }
}
