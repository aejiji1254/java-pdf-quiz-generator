package com.quiz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** 행은 A, 열은 제시 설명이다. 배열 인덱스와 seed로 섞은 List만 순회한다. */
final class OxAllocator {
    private final List<Term> terms;
    private final List<List<Integer>> alternatives = new ArrayList<>();
    private final List<Integer> order = new ArrayList<>();
    private final boolean preferMoreX;
    private final int n;

    private record Component(List<Integer> vertices, boolean cycle) { }
    private record Choice(Component component, int x, int donors, long distance) { }
    private record State(State previous, Choice choice, int x, int donors, long distance) { }

    OxAllocator(List<Term> terms, Random random) {
        this.terms = terms;
        n = terms.size();
        for (int a = 0; a < n; a++) {
            List<Integer> candidates = new ArrayList<>();
            for (int b = 0; b < n; b++) if (QuizGenerator.isDistractor(terms.get(a), terms.get(b))) candidates.add(b);
            Collections.shuffle(candidates, random);
            Term anchor = terms.get(a);
            candidates.sort(Comparator.comparingInt(b -> QuizGenerator.descriptionDistance(anchor, terms.get(b))));
            alternatives.add(candidates);
            order.add(a);
        }
        Collections.shuffle(order, random);
        preferMoreX = random.nextBoolean();
    }

    boolean hasAlternative(int index) { return !alternatives.get(index).isEmpty(); }

    int[] allocate() {
        boolean[] blocked = new boolean[n];
        State best = bestPlan(components(maximumMatching(blocked), blocked));
        // 홀수 닫힌 매칭도 시도해 두 항목 교환만으로 만들 수 없는 홀수 개 X를 보존한다.
        for (List<Integer> cycle : oddCycles()) {
            Arrays.fill(blocked, false);
            for (int vertex : cycle) blocked[vertex] = true;
            List<Component> cover = new ArrayList<>();
            cover.add(new Component(cycle, true));
            cover.addAll(components(maximumMatching(blocked), blocked));
            State candidate = bestPlan(cover);
            if (better(candidate, best)) best = candidate;
        }
        int[] shown = new int[n];
        for (int i = 0; i < n; i++) shown[i] = i;
        for (State state = best; state.choice() != null; state = state.previous()) apply(shown, state.choice());
        return shown;
    }

    private int[] maximumMatching(boolean[] blocked) {
        int[] columnOwner = new int[n];
        Arrays.fill(columnOwner, -1);
        for (int row : order) if (!blocked[row]) augment(row, new boolean[n], blocked, columnOwner);
        int[] shown = new int[n];
        Arrays.fill(shown, -1);
        for (int b = 0; b < n; b++) if (columnOwner[b] >= 0) shown[columnOwner[b]] = b;
        return shown;
    }

    private boolean augment(int row, boolean[] seen, boolean[] blocked, int[] owner) {
        for (int column : alternatives.get(row)) {
            if (blocked[column] || seen[column]) continue;
            seen[column] = true;
            if (owner[column] < 0 || augment(owner[column], seen, blocked, owner)) {
                owner[column] = row;
                return true;
            }
        }
        return false;
    }

    private List<Component> components(int[] shown, boolean[] blocked) {
        boolean[] seen = blocked.clone();
        int[] incoming = new int[n];
        for (int b : shown) if (b >= 0) incoming[b]++;
        List<Component> result = new ArrayList<>();
        for (int start : order) if (!seen[start] && incoming[start] == 0 && shown[start] >= 0)
            result.add(component(start, shown, seen, false));
        for (int start : order) if (!seen[start] && shown[start] >= 0)
            result.add(component(start, shown, seen, true));
        return result;
    }

    private Component component(int start, int[] shown, boolean[] seen, boolean cycle) {
        List<Integer> vertices = new ArrayList<>();
        for (int vertex = start; vertex >= 0 && !seen[vertex]; vertex = shown[vertex]) {
            seen[vertex] = true;
            vertices.add(vertex);
        }
        return new Component(vertices, cycle);
    }

    private List<List<Integer>> oddCycles() {
        List<List<Integer>> cycles = new ArrayList<>();
        for (int root : order) {
            int[] depth = new int[n], parent = new int[n];
            Arrays.fill(depth, -1); Arrays.fill(parent, -1);
            List<Integer> queue = new ArrayList<>();
            queue.add(root); depth[root] = 0;
            List<Integer> shortest = null;
            for (int cursor = 0; cursor < queue.size() && (shortest == null || shortest.size() > 3); cursor++) {
                int a = queue.get(cursor);
                for (int b : alternatives.get(a)) {
                    if (depth[b] < 0) {
                        depth[b] = depth[a] + 1; parent[b] = a; queue.add(b);
                    } else if (depth[a] % 2 == depth[b] % 2) {
                        List<Integer> candidate = cycle(a, b, parent);
                        if (shortest == null || candidate.size() < shortest.size()) shortest = candidate;
                    }
                }
            }
            if (shortest != null) cycles.add(shortest);
        }
        return cycles;
    }

    private List<Integer> cycle(int a, int b, int[] parent) {
        List<Integer> left = new ArrayList<>(), right = new ArrayList<>();
        for (int vertex = a; vertex >= 0; vertex = parent[vertex]) left.add(vertex);
        int common = b;
        while (!left.contains(common)) { right.add(common); common = parent[common]; }
        List<Integer> result = new ArrayList<>(left.subList(0, left.indexOf(common) + 1));
        result.addAll(right.reversed());
        return result;
    }

    private State bestPlan(List<Component> cover) {
        State[][] states = new State[n + 1][2];
        states[0][0] = new State(null, null, 0, 0, 0);
        for (Component component : cover) {
            State[][] next = new State[n + 1][2];
            for (int x = 0; x <= component.vertices().size(); x++) {
                boolean closedOdd = component.cycle() && x % 2 == 1 && x == component.vertices().size();
                int donors = x % 2 == 1 && !closedOdd ? 1 : 0;
                if (donors == 1 && x + 1 > component.vertices().size()) continue;
                Choice choice = new Choice(component, x, donors, distance(component, x, closedOdd));
                for (int previousX = 0; previousX + x <= n; previousX++) for (int previousD = 0; previousD + donors <= 1; previousD++) {
                    State previous = states[previousX][previousD];
                    if (previous == null) continue;
                    State candidate = new State(previous, choice, previousX + x, previousD + donors,
                            previous.distance() + choice.distance());
                    State old = next[candidate.x()][candidate.donors()];
                    if (old == null || candidate.distance() < old.distance()) next[candidate.x()][candidate.donors()] = candidate;
                }
            }
            states = next;
        }
        State best = null;
        for (State[] row : states) for (State state : row) if (state != null && better(state, best)) best = state;
        return best;
    }

    private boolean better(State a, State b) {
        if (b == null) return true;
        int qa = n - a.donors(), qb = n - b.donors();
        int da = Math.abs(qa - 2 * a.x()), db = Math.abs(qb - 2 * b.x());
        if ((da <= 1) != (db <= 1)) return da <= 1;
        if (da <= 1 && qa != qb) return qa > qb;
        if (da != db) return da < db;
        if (qa != qb) return qa > qb;
        if (a.x() != b.x()) return preferMoreX ? a.x() > b.x() : a.x() < b.x();
        return a.distance() < b.distance();
    }

    private long distance(Component component, int x, boolean closedOdd) {
        List<Integer> v = component.vertices();
        long distance = 0;
        if (closedOdd) {
            for (int i = 0; i < x; i++) distance += difference(v.get(i), v.get((i + 1) % x));
        } else {
            for (int i = 0; i + 1 < x; i += 2) distance += 2L * difference(v.get(i), v.get(i + 1));
            if (x % 2 == 1) distance += difference(v.get(x - 1), v.get(x));
        }
        return distance;
    }

    private int difference(int a, int b) { return QuizGenerator.descriptionDistance(terms.get(a), terms.get(b)); }

    private void apply(int[] shown, Choice choice) {
        List<Integer> v = choice.component().vertices();
        int x = choice.x();
        if (choice.component().cycle() && x % 2 == 1 && x == v.size()) {
            for (int i = 0; i < x; i++) shown[v.get(i)] = v.get((i + 1) % x);
        } else {
            for (int i = 0; i + 1 < x; i += 2) { shown[v.get(i)] = v.get(i + 1); shown[v.get(i + 1)] = v.get(i); }
            if (x % 2 == 1) { shown[v.get(x - 1)] = v.get(x); shown[v.get(x)] = -1; }
        }
    }
}
