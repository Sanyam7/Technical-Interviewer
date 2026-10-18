import java.util.*;

class Solution {
    public int lengthOfLIS(int[] nums) {
        int[] tails = new int[nums.length];
        int size = 0;
        for (int value : nums) {
            int position = Arrays.binarySearch(tails, 0, size, value);
            if (position < 0) {
                position = -position - 1;
            }
            tails[position] = value;
            if (position == size) {
                size++;
            }
        }
        return size;
    }
}
