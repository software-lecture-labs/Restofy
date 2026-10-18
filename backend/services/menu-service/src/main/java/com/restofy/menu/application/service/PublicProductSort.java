package com.restofy.menu.application.service;

/**
 * Sort of the public product list. The field is an enum, never client text, so nothing the client typed can
 * reach the query as a column or an expression. The primary key is always appended as the last tie-breaker.
 */
public record PublicProductSort(Field field, boolean ascending) {

    public static final PublicProductSort DEFAULT = new PublicProductSort(Field.NAME, true);

    public enum Field { NAME, BASE_PRICE, CREATED_AT }
}
