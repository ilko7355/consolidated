package com.ilko.tournament.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Round-robin schedule for one group, built with the circle method (the basis of Berger tables).
 *
 * <p>Positions 0..n-1 are placed on a ring; in every round position i plays position n-1-i, then every
 * position except the first rotates one step. After n-1 rounds each pair has met exactly once and nobody
 * plays twice in the same round. With an odd participant count an extra "rest" position is added, and
 * whoever is paired with it sits that round out.</p>
 */
@Component
public class RoundRobinScheduler {

    /**
     * Returns the rounds of the schedule; each round lists pairs of indexes into the participant list,
     * the lower index first.
     */
    public List<List<int[]>> schedule(int participantCount) {
        if (participantCount < 2) {
            return List.of();
        }
        int slots = participantCount % 2 == 0 ? participantCount : participantCount + 1;
        int[] ring = new int[slots];
        for (int i = 0; i < slots; i++) {
            ring[i] = i;
        }

        List<List<int[]>> rounds = new ArrayList<>();
        for (int round = 0; round < slots - 1; round++) {
            List<int[]> pairs = new ArrayList<>();
            for (int i = 0; i < slots / 2; i++) {
                int a = ring[i];
                int b = ring[slots - 1 - i];
                if (a < participantCount && b < participantCount) {
                    pairs.add(new int[]{Math.min(a, b), Math.max(a, b)});
                }
            }
            rounds.add(pairs);

            int last = ring[slots - 1];
            System.arraycopy(ring, 1, ring, 2, slots - 2);
            ring[1] = last;
        }
        return rounds;
    }
}
