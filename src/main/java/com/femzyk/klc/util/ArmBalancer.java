package com.femzyk.klc.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ArmBalancer - deterministic, explainable class-arm balancing (directive
 * F6). Given the students of one class level/session and the ordered arm
 * list, it computes the minimum set of arm reassignments that levels the
 * arms.
 *
 * Rules (kept simple and auditable):
 *  - target size for the first {@code total % arms} arms (in the given arm
 *    order) is {@code total/arms + 1}; every later arm gets
 *    {@code total/arms} (empty class yields all-zero targets);
 *  - students are moved only out of arms ABOVE their target;
 *  - which students move is deterministic: the over-full arm's students are
 *    considered in admission-number order and the first
 *    {@code count - target} are moved;
 *  - movers are assigned to the most under-full arms (largest deficit
 *    first, arm order as tie-break).
 *
 * The planner never touches admission numbers, results, history or the
 * students table - it only proposes arm changes (application + audit live
 * in the caller, which must record an audit row per reassignment).
 */
public final class ArmBalancer {

    private ArmBalancer() {}

    /** A student within one class level (id + name + admission + current arm). */
    public static final class Student {
        public final String userId;
        public final String name;
        public final String admissionNo;
        /** Current arm - mutable so the caller can apply a proposed Move. */
        public String arm;
        public Student(String userId, String name, String admissionNo,
                       String arm) {
            this.userId = userId;
            this.name = name == null ? "" : name;
            this.admissionNo = admissionNo == null ? "" : admissionNo;
            this.arm = arm == null ? "" : arm;
        }
    }

    /** One proposed arm reassignment (to be reviewed by the administrator). */
    public static final class Move {
        public final String userId;
        public final String name;
        public final String admissionNo;
        public final String fromArm;
        public final String toArm;
        public Move(String userId, String name, String admissionNo,
                    String fromArm, String toArm) {
            this.userId = userId;
            this.name = name;
            this.admissionNo = admissionNo;
            this.fromArm = fromArm;
            this.toArm = toArm;
        }
    }

    /**
     * @param students    students of the class (current arms attached)
     * @param orderedArms the arms in the school's preferred order, e.g.
     *                    [A, B, C]
     * @return proposed moves (empty when already balanced or no arms given)
     */
    public static List<Move> plan(List<Student> students,
                                  List<String> orderedArms) {
        List<Move> moves = new ArrayList<>();
        if (students == null || students.isEmpty()
                || orderedArms == null || orderedArms.isEmpty()) {
            return moves;
        }

        // Stable grouping by arm (admission order preserved)
        Map<String, List<Student>> byArm = new LinkedHashMap<>();
        for (String arm : orderedArms) byArm.put(arm, new ArrayList<>());
        for (Student s : students) {
            if (s.arm != null && !s.arm.isBlank()) {
                List<Student> l = byArm.computeIfAbsent(s.arm.trim(),
                    k -> new ArrayList<>());
                l.add(s);
            }
        }
        for (List<Student> l : byArm.values()) {
            l.sort(Comparator.comparing(st -> st.admissionNo));
        }

        int total = students.size();
        int arms  = orderedArms.size();
        int base  = total / arms;
        int rem   = total % arms;

        int[] target = new int[arms];
        for (int i = 0; i < arms; i++) target[i] = base + (i < rem ? 1 : 0);

        // Deficits per arm (ordered): how many students each arm can take
        int[] deficit = new int[arms];
        for (int i = 0; i < arms; i++) {
            List<Student> cur = byArm.get(orderedArms.get(i));
            int size = cur == null ? 0 : cur.size();
            deficit[i] = Math.max(0, target[i] - size);
        }

        // Move deterministically: for each over-full arm (in arm order) move
        // the first (size - target) students into the most under-full arms.
        for (int i = 0; i < arms; i++) {
            List<Student> cur = byArm.get(orderedArms.get(i));
            int size = cur == null ? 0 : cur.size();
            int excess = size - target[i];
            for (int k = 0; k < excess; k++) {
                // pick the arm with the largest remaining deficit
                int best = -1;
                for (int j = 0; j < arms; j++) {
                    if (deficit[j] > 0
                            && (best < 0 || deficit[j] > deficit[best])) {
                        best = j;
                    }
                }
                if (best < 0) break; // nowhere left to go (should not happen)
                Student s = cur.get(k);
                moves.add(new Move(s.userId, s.name, s.admissionNo,
                    orderedArms.get(i), orderedArms.get(best)));
                deficit[best]--;
            }
        }
        return moves;
    }
}
