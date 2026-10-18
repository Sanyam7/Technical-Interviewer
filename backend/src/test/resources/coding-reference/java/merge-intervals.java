import java.util.*;

class Solution {
    public int[][] merge(int[][] intervals) {
        int[][] sorted = intervals.clone();
        Arrays.sort(sorted, Comparator.comparingInt(interval -> interval[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] interval : sorted) {
            if (!merged.isEmpty() && interval[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], interval[1]);
            } else {
                merged.add(new int[] {interval[0], interval[1]});
            }
        }
        return merged.toArray(new int[0][]);
    }
}
