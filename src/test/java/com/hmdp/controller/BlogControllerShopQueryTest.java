package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IBlogService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class BlogControllerShopQueryTest {
    @Test
    void shopQueryRejectsInvalidShopAndPageBeforeServiceCall() {
        IBlogService service = mock(IBlogService.class);
        BlogController controller = new BlogController();
        ReflectionTestUtils.setField(controller, "blogService", service);

        Result invalidShop = controller.queryBlogByShopId(0L, 1);
        Result invalidPage = controller.queryBlogByShopId(12L, 0);
        Result beyondLimit = controller.queryBlogByShopId(12L, 101);

        assertFalse(invalidShop.getSuccess());
        assertTrue(invalidShop.getErrorMsg().contains("店铺 ID"));
        assertFalse(invalidPage.getSuccess());
        assertTrue(invalidPage.getErrorMsg().contains("页码"));
        assertFalse(beyondLimit.getSuccess());
        assertTrue(beyondLimit.getErrorMsg().contains("前1000条"));
        verifyNoInteractions(service);
    }

    @Test
    void shopQueryDelegatesShopAndPageToService() {
        IBlogService service = mock(IBlogService.class);
        BlogController controller = new BlogController();
        ReflectionTestUtils.setField(controller, "blogService", service);
        Result expected = Result.ok();
        when(service.queryBlogByShopId(12L, 2)).thenReturn(expected);

        assertSame(expected, controller.queryBlogByShopId(12L, 2));

        verify(service).queryBlogByShopId(12L, 2);
    }
}
