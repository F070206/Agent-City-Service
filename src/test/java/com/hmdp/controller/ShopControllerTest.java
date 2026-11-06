package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ShopControllerTest {
    @Test
    void nameSearchRejectsInvalidPageBeforeQueryingDatabase() {
        IShopService service = mock(IShopService.class);
        ShopController controller = new ShopController();
        controller.shopService = service;

        Result result = controller.queryShopByName("火锅", 0);

        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("页码"));
        verifyNoInteractions(service);
    }

    @Test
    void nameSearchLimitsResultsToFirstThousandRows() {
        IShopService service = mock(IShopService.class);
        ShopController controller = new ShopController();
        controller.shopService = service;

        Result result = controller.queryShopByName("火锅", 101);

        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("前1000条"));
        verifyNoInteractions(service);
    }

    @Test
    void typeSearchPassesKeywordToService() {
        IShopService service = mock(IShopService.class);
        ShopController controller = new ShopController();
        controller.shopService = service;
        Result expected = Result.ok(java.util.List.of());
        when(service.queryShopByType(3, 2, null, null, "火锅")).thenReturn(expected);

        Result actual = controller.queryShopByType(3, 2, null, null, "火锅");

        assertSame(expected, actual);
        verify(service).queryShopByType(3, 2, null, null, "火锅");
    }

    @Test
    void createDelegatesToShopServiceResult() {
        IShopService service = mock(IShopService.class);
        ShopController controller = new ShopController();
        controller.shopService = service;
        Shop shop = new Shop();
        Result rejected = Result.fail("保存店铺失败");
        when(service.saveShop(shop)).thenReturn(rejected);
        ReflectionTestUtils.setField(controller, "adminApiGuard",
                new com.hmdp.config.AdminApiGuard("secret"));

        assertSame(rejected, controller.saveShop("secret", shop));
        verify(service).saveShop(shop);
        verify(service, never()).save(shop);
    }
}
