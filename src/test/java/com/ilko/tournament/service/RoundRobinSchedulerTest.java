package com.ilko.tournament.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoundRobinSchedulerTest {
    private final RoundRobinScheduler scheduler = new RoundRobinScheduler();

    @ParameterizedTest(name = "{0} participants")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10})
    void everyPairMeetsExactlyOnceAndNobodyPlaysTwiceInARound(int count) {
        List<List<int[]>> rounds = scheduler.schedule(count);

        assertEquals(count % 2 == 0 ? count - 1 : count, rounds.size());
        Set<String> pairs = new HashSet<>();
        for (List<int[]> round : rounds) {
            Set<Integer> busy = new HashSet<>();
            for (int[] pair : round) {
                assertTrue(pair[0] < pair[1], "Lower index is listed first");
                assertTrue(busy.add(pair[0]), "Participant " + pair[0] + " plays twice in one round");
                assertTrue(busy.add(pair[1]), "Participant " + pair[1] + " plays twice in one round");
                assertTrue(pairs.add(pair[0] + "-" + pair[1]), "Pair " + pair[0] + "-" + pair[1] + " is scheduled twice");
            }
        }
        assertEquals(count * (count - 1) / 2, pairs.size());
    }

    @Test
    void fewerThanTwoParticipantsNeedNoMatches() {
        assertTrue(scheduler.schedule(0).isEmpty());
        assertTrue(scheduler.schedule(1).isEmpty());
    }
}
