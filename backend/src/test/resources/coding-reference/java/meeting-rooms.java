import java.util.*;

class Solution {
    public int minMeetingRooms(int[][] intervals) {
        int[][] sorted = intervals.clone();
        Arrays.sort(sorted, Comparator.comparingInt(interval -> interval[0]));
        PriorityQueue<Integer> ends = new PriorityQueue<>();
        for (int[] meeting : sorted) {
            if (!ends.isEmpty() && ends.peek() <= meeting[0]) {
                ends.poll();
            }
            ends.add(meeting[1]);
        }
        return ends.size();
    }
}
