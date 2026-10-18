import java.util.*;

class Solution {
    public int lengthOfLongestSubstring(String s) {
        Map<Character, Integer> last = new HashMap<>();
        int start = 0;
        int best = 0;
        for (int i = 0; i < s.length(); i++) {
            Integer previous = last.put(s.charAt(i), i);
            if (previous != null && previous >= start) {
                start = previous + 1;
            }
            best = Math.max(best, i - start + 1);
        }
        return best;
    }
}
