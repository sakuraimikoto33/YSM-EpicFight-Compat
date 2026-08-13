package net.okitsu.ysmepicfightcompat.geometry;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockGeometryParserTest {
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
}
