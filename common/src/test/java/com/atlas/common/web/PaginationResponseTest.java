package com.atlas.common.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaginationResponseTest {

    @Test
    void factoryMethodSetsAllFields() {
        List<String> content = List.of("a", "b", "c");

        PaginationResponse<String> response = PaginationResponse.of(content, 0, 10, 25);

        assertEquals(content, response.content());
        assertEquals(0, response.page());
        assertEquals(10, response.size());
        assertEquals(25, response.totalElements());
        assertEquals(3, response.totalPages());
        assertTrue(response.first());
        assertFalse(response.last());
    }

    @Test
    void calculatesTotalPagesCorrectly() {
        PaginationResponse<String> response = PaginationResponse.of(List.of(), 0, 10, 25);
        assertEquals(3, response.totalPages());
    }

    @Test
    void exactFitCalculatesTotalPages() {
        PaginationResponse<String> response = PaginationResponse.of(List.of(), 0, 10, 30);
        assertEquals(3, response.totalPages());
    }

    @Test
    void firstPageFlagTrueForPageZero() {
        PaginationResponse<String> response = PaginationResponse.of(List.of(), 0, 10, 50);
        assertTrue(response.first());
    }

    @Test
    void firstPageFlagFalseForNonZeroPage() {
        PaginationResponse<String> response = PaginationResponse.of(List.of(), 1, 10, 50);
        assertFalse(response.first());
    }

    @Test
    void lastPageFlagTrueForLastPage() {
        PaginationResponse<String> response = PaginationResponse.of(List.of(), 4, 10, 50);
        assertTrue(response.last());
    }

    @Test
    void lastPageFlagFalseForNonLastPage() {
        PaginationResponse<String> response = PaginationResponse.of(List.of(), 3, 10, 50);
        assertFalse(response.last());
    }

    @Test
    void singlePageIsFirstAndLast() {
        PaginationResponse<String> response = PaginationResponse.of(List.of("a"), 0, 10, 5);
        assertTrue(response.first());
        assertTrue(response.last());
    }

    @Test
    void emptyResultReturnsZeroTotalPages() {
        PaginationResponse<String> response = PaginationResponse.of(List.of(), 0, 10, 0);
        assertEquals(0, response.totalPages());
        assertEquals(0, response.totalElements());
        assertTrue(response.first());
        assertTrue(response.last());
    }

    @Test
    void zeroSizeReturnsZeroTotalPages() {
        PaginationResponse<String> response = PaginationResponse.of(List.of(), 0, 0, 100);
        assertEquals(0, response.totalPages());
        assertTrue(response.last());
    }

    @Test
    void singleElementSinglePage() {
        List<Integer> content = List.of(42);

        PaginationResponse<Integer> response = PaginationResponse.of(content, 0, 10, 1);

        assertEquals(1, response.totalPages());
        assertTrue(response.first());
        assertTrue(response.last());
        assertEquals(1, response.totalElements());
    }

    @Test
    void directConstructionPreservesAllFields() {
        List<String> content = List.of("x");

        PaginationResponse<String> response = new PaginationResponse<>(content, 2, 5, 15, 3, false, true);

        assertEquals(content, response.content());
        assertEquals(2, response.page());
        assertEquals(5, response.size());
        assertEquals(15, response.totalElements());
        assertEquals(3, response.totalPages());
        assertFalse(response.first());
        assertTrue(response.last());
    }

    @Test
    void middlePageIsNeitherFirstNorLast() {
        PaginationResponse<String> response = PaginationResponse.of(List.of(), 1, 10, 30);
        assertFalse(response.first());
        assertFalse(response.last());
    }
}
