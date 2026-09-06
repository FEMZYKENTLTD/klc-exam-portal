package com.femzyk.klc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Arm-balancing planner tests (directive §15): deterministic fixtures,
 * balanced classes produce no moves, unbalanced classes produce the minimum
 * explainable moves, and applying the proposed moves must level every arm.
 */
class ArmBalancerTest {

    private static ArmBalancer.Student stu(String id, String adm, String arm) {
        return new ArmBalancer.Student(id, "Student " + id, adm, arm);
    }

    private static List<ArmBalancer.Student> classOf(int a, int b, int c) {
        List<ArmBalancer.Student> list = new ArrayList<>();
        for (int i = 1; i <= a; i++)
            list.add(stu("a" + i, String.format("ADM-A%03d", i), "A"));
        for (int i = 1; i <= b; i++)
            list.add(stu("b" + i, String.format("ADM-B%03d", i), "B"));
        for (int i = 1; i <= c; i++)
            list.add(stu("c" + i, String.format("ADM-C%03d", i), "C"));
        return list;
    }

    private static Map<String, Integer> armCounts(
            List<ArmBalancer.Student> students) {
        Map<String, Integer> counts = new HashMap<>();
        for (ArmBalancer.Student s : students)
            counts.merge(s.arm, 1, Integer::sum);
        return counts;
    }

    @Test
    void perfectlyBalancedClassProducesNoMoves() {
        List<ArmBalancer.Student> s = classOf(5, 5, 5);
        assertTrue(ArmBalancer.plan(s, List.of("A", "B", "C")).isEmpty(),
            "balanced class must need no moves");
    }

    @Test
    void evenDistributionMovesMinimum() {
        // 15 students across 3 arms -> targets A=5,B=5,C=5
        List<ArmBalancer.Student> s = classOf(7, 5, 3);
        List<ArmBalancer.Move> moves =
            ArmBalancer.plan(s, List.of("A", "B", "C"));

        // A has excess 2 -> exactly 2 moves; C receives 2
        assertEquals(2, moves.size());
        for (ArmBalancer.Move m : moves) {
            assertEquals("A", m.fromArm);
            assertEquals("C", m.toArm);
        }
        // deterministic: same input, same output
        assertEquals(moves.toString(),
            ArmBalancer.plan(s, List.of("A", "B", "C")).toString());
    }

    @Test
    void applyingProposedMovesLevelsArms() {
        // 10 students across 3 arms -> targets A=4,B=3,C=3
        List<ArmBalancer.Student> s = classOf(6, 2, 2);
        List<ArmBalancer.Move> moves = ArmBalancer.plan(s, List.of("A", "B", "C"));

        for (ArmBalancer.Move m : moves) {
            for (ArmBalancer.Student st : s) {
                if (st.userId.equals(m.userId)) {
                    st.arm = m.toArm; // applying the move
                    break;
                }
            }
        }
        Map<String, Integer> counts = armCounts(s);
        assertEquals(4, counts.get("A"));
        assertEquals(3, counts.get("B"));
        assertEquals(3, counts.get("C"));
    }

    @Test
    void fiveAcrossTwoArmsGoesThreeTwo() {
        List<ArmBalancer.Student> s = classOf(3, 2, 0);
        List<ArmBalancer.Move> moves = ArmBalancer.plan(s, List.of("A", "B"));
        // targets: A=3,B=2 -> already balanced (3+2=5, rem1 -> A=3,B=2)
        assertTrue(moves.isEmpty());
    }

    @Test
    void imbalanceAcrossTwoArmsMovesOne() {
        List<ArmBalancer.Student> s = classOf(4, 2, 0);
        List<ArmBalancer.Move> moves = ArmBalancer.plan(s, List.of("A", "B"));
        // targets A=3,B=3
        assertEquals(1, moves.size());
        assertEquals("A", moves.get(0).fromArm);
        assertEquals("B", moves.get(0).toArm);
    }

    @Test
    void emptyAndSingleArmEdgeCases() {
        assertTrue(ArmBalancer.plan(new ArrayList<>(), List.of("A", "B"))
            .isEmpty());
        List<ArmBalancer.Student> one = new ArrayList<>();
        one.add(stu("x1", "ADM-1", "A"));
        assertTrue(ArmBalancer.plan(one, List.of("A")).isEmpty(),
            "single arm cannot need balancing");
    }
}
