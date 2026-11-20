package com.vn.vitalcare.share.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ListParamsFoldTest {

    @Test
    void foldsCaseAndVietnameseMarks() {
        assertThat(ListParams.foldedPattern("TRẦN THỊ BÍCH")).isEqualTo("%tran thi bich%");
        assertThat(ListParams.foldedPattern("tran thi bich")).isEqualTo("%tran thi bich%");
        assertThat(ListParams.foldedPattern("Đặng Ưu Ỹ")).isEqualTo("%dang uu y%");
        // Decomposed input (a mark typed as its own code point) folds like the composed one.
        assertThat(ListParams.foldedPattern("Trần")).isEqualTo("%tran%");
    }
}
