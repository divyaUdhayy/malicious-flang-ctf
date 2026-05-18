package org.mortimer;

public class Main {

    private static final String password = "thisIsThePassword_Clever_no?";
    private static final String secret_flag = "FLAG{hiding_data_in_an_encoded_dex_class_TM}";


    public static void main(String[] args) {
        char[] passwordChar = password.toCharArray();

        byte[] encryptedBytes = HiddenCompiledDexClass.mergeThemTogether(secret_flag.getBytes(), passwordChar);

        StringBuilder sb = new StringBuilder();
        for (byte b : encryptedBytes) {
            if (sb.isEmpty()) {
                sb.append("Encrypted Byte Array: {");
            } else {
                sb.append(", ");
            }
            sb.append(b);
        }
        sb.append("}");
        System.out.println(sb);

        System.out.println("Ceaser:" + HiddenCompiledDexClass.letsJumpAgain("Clever", 6));


        System.out.println("Sanity check: " + HiddenCompiledDexClass.checkParameter(password));
    }
}
