class Solution {
    public boolean isPalindrome(String s) {
        int left = 0;
        int right = s.length() - 1;
        while (left < right) {
            char a = s.charAt(left);
            char b = s.charAt(right);
            if (!Character.isLetterOrDigit(a)) {
                left++;
            } else if (!Character.isLetterOrDigit(b)) {
                right--;
            } else if (Character.toLowerCase(a) != Character.toLowerCase(b)) {
                return false;
            } else {
                left++;
                right--;
            }
        }
        return true;
    }
}
