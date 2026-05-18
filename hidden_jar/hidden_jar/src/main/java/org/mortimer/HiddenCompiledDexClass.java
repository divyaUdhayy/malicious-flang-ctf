package org.mortimer;

public class HiddenCompiledDexClass {

    private static final char separator = '_';
    private static final char[] jumpPlus = {'I', 'r', 'k', 'b', 'k', 'x'};
    private static final char[] arrayOfCharsAgainWithATwist = {'s', 'I'};
    private static final String keepGoing = "ThePassword";
    private static final char[] arrayOfChars = {'t', 'h', 'i', 's'};
    private static final char[] slightChangeAgain = {'?', 'o', 'n'};
    private static final byte[] whatAreThese = {50, 36, 40, 52, 50, 27, 61, 12, 12, 62, 6, 44, 23, 22, 27, 19, 59, 54, 45, 51, 4, 24, 58, 23, 49, 13, 0, 91, 17, 12, 54, 23, 44, 11, 11, 11, 9, 49, 18, 0, 44, 35, 34, 15};

    //Expected to be unused, as will be called from outside the class
    @SuppressWarnings("unused")
    public static String checkParameter(String parameter) {
        String valueCheck = new String(arrayOfChars)
                + arrayOfCharsAgainWithATwist[1]
                + arrayOfCharsAgainWithATwist[0]
                + keepGoing
                + separator
                + letsJumpAgain(new String(jumpPlus), -6)
                + separator
                + slightChangeAgain[2]
                + slightChangeAgain[1]
                + slightChangeAgain[0];
        if (parameter.equals(valueCheck)) {
            return new String(mergeThemTogether(whatAreThese, parameter.toCharArray()));
        }
        return "\"Game over, man! Game over!\" - Hicks, Aliens";
    }

    protected static String letsJumpAgain(String text, int shift) {
        StringBuilder result = new StringBuilder();
        for (char character : text.toCharArray()) {
            if (Character.isLetter(character)) {
                char base = Character.isUpperCase(character) ? 'A' : 'a';
                character = (char) (base + (character - base + shift + 26) % 26);
            }
            result.append(character);
        }
        return result.toString();
    }

    protected static byte[] mergeThemTogether(byte[] inputData, char[] password) {
        byte[] output = new byte[inputData.length];
        for (int i = 0; i < inputData.length; i++) {
            char keyChar = password[i % password.length];
            output[i] = (byte) (inputData[i] ^ keyChar);
        }

        return output;
    }
}
