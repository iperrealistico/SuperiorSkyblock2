package com.bgsoftware.superiorskyblock.pvc.island;
import com.bgsoftware.superiorskyblock.core.IslandArea;
import org.junit.Test;
import static org.junit.Assert.*;
public class IslandAreaPublicationTest {
    @Test public void inclusiveBlockAndExclusiveContinuousEdgesRemainCorrect() {
        IslandArea area = new IslandArea(); area.update(-100, -100, 100, 100);
        assertTrue(area.intercepts(-100, -100)); assertTrue(area.intercepts(100.99, 100.99));
        assertFalse(area.intercepts(101, 100)); assertFalse(area.intercepts(-100.01, 0));
    }
    @Test public void negativeChunkCoordinatesUseFloorAndArithmeticShift() {
        IslandArea area = new IslandArea(); area.update(-17, -17, -1, -1);
        assertTrue(area.expandRshiftAndIntercepts(-2, -2, 0, 4));
        assertTrue(area.expandRshiftAndIntercepts(-1, -1, 0, 4));
        assertFalse(area.expandRshiftAndIntercepts(0, 0, 0, 4));
    }
}
