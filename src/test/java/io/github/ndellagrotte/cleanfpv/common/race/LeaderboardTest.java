package io.github.ndellagrotte.cleanfpv.common.race;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);

    @Test
    void keepsEachPilotsBestLapNotTheLatest() {
        Leaderboard board = new Leaderboard();
        assertTrue(board.submit(A, 50_000));
        assertTrue(board.submit(A, 45_000));
        assertFalse(board.submit(A, 47_000));
        assertFalse(board.submit(A, 45_000));
        assertEquals(45_000, board.bestMs(A));
        assertEquals(-1, board.bestMs(B));
        assertFalse(board.submit(B, 0));
        assertFalse(board.submit(null, 10));
    }

    @Test
    void ranksFastestFirstWithStableTies() {
        Leaderboard board = new Leaderboard();
        board.submit(C, 30_000);
        board.submit(B, 20_000);
        board.submit(A, 30_000);
        List<Leaderboard.Entry> all = board.all();
        assertEquals(List.of(new Leaderboard.Entry(B, 20_000), new Leaderboard.Entry(A, 30_000),
                new Leaderboard.Entry(C, 30_000)), all);
        assertEquals(1, board.rank(B));
        assertEquals(2, board.rank(A));
        assertEquals(3, board.rank(C));
        assertEquals(-1, board.rank(new UUID(9, 9)));
        assertEquals(2, board.top(2).size());
        assertEquals(3, board.top(10).size());
        board.clear();
        assertTrue(board.isEmpty());
    }

    @Test
    void lapTimesFormatConventionally() {
        assertEquals("0:00.00", LapTimes.format(0));
        assertEquals("0:00.00", LapTimes.format(-5));
        assertEquals("0:00.05", LapTimes.format(59));
        assertEquals("0:07.50", LapTimes.format(7_500));
        assertEquals("1:01.23", LapTimes.format(61_234));
        assertEquals("59:59.99", LapTimes.format(3_599_999));
        assertEquals("1:00:00.00", LapTimes.format(3_600_000));
    }
}
