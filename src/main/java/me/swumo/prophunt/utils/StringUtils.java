package me.swumo.prophunt.utils;

import java.util.Iterator;
import java.util.List;

public class StringUtils {

    private StringUtils() {
        throw new IllegalStateException("This is a utility class.");
    }

    public static String capitalize(String string) {
        if (string == null) string = "";
        string = string.replaceAll("_", " ");
        final int length = string.length();
        final char[] capitalized = new char[length];

        char character, previous = ' ';

        for (int i = 0; i < length; ++i) {
            character = string.charAt(i);

            if (character >= 65 && character <= 90) {
                capitalized[i] = previous == ' ' ? character : (char) (character + 32);
            } else if (character >= 97 && character <= 122) {
                capitalized[i] = previous == ' ' ? (char) (character - 32) : character;
            } else {
                capitalized[i] = character;
            }
            previous = character;
        }
        return new String(capitalized);
    }

    /**
     * Note: This was easier to handle the "toJoin" as a String as it should never really need to join proper objects together.
     */
    public static String join(List<?> toJoin, String delimiter) {
        if (delimiter == null) {
            delimiter = "";
        }

        StringBuilder stringBuilder = new StringBuilder();
        Iterator<?> iterator = toJoin.iterator();
        String value;

        for (boolean first = true; iterator.hasNext(); stringBuilder.append(value)) {
            value = iterator.next().toString();

            if (first) {
                first = false;
            } else {
                stringBuilder.append(delimiter);
            }
        }

        return stringBuilder.toString();
    }

}
