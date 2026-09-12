package com.ilko.tournament.enums;

/**
 * Which part of a double-elimination bracket a match belongs to.
 *
 * <p>Only DOUBLE_ELIMINATION matches carry a value; it stays {@code null} for single-elimination
 * brackets and for group matches, where there is nothing to distinguish.</p>
 */
public enum BracketSide { WINNERS, LOSERS, GRAND_FINAL }
