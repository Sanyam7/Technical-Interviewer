class Solution {
    public int minDistance(String word1, String word2) {
        int[] previous = new int[word2.length() + 1];
        for (int j = 0; j <= word2.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= word1.length(); i++) {
            int[] current = new int[word2.length() + 1];
            current[0] = i;
            for (int j = 1; j <= word2.length(); j++) {
                current[j] = word1.charAt(i - 1) == word2.charAt(j - 1)
                        ? previous[j - 1]
                        : 1 + Math.min(previous[j - 1], Math.min(previous[j], current[j - 1]));
            }
            previous = current;
        }
        return previous[word2.length()];
    }
}
