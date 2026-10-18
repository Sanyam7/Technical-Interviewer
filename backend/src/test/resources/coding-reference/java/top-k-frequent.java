import java.util.*;

class Solution {
    public int[] topKFrequent(int[] nums, int k) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int value : nums) {
            counts.merge(value, 1, Integer::sum);
        }
        List<Integer> values = new ArrayList<>(counts.keySet());
        values.sort((a, b) -> counts.get(a).equals(counts.get(b)) ? Integer.compare(a, b) : Integer.compare(counts.get(b), counts.get(a)));
        int[] result = new int[k];
        for (int i = 0; i < k; i++) {
            result[i] = values.get(i);
        }
        return result;
    }
}
