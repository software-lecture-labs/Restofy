package com.restofy.menu.domain.enums;

/**
 * The two kinds of product option. {@link #INCLUDED} is part of the product and may be removable or mandatory;
 * {@link #ADD} is an optional extra with a price difference (possibly zero).
 */
public enum OptionType {
    INCLUDED,
    ADD
}
