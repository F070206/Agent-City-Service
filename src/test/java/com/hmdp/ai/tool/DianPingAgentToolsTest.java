package com.hmdp.ai.tool;

import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.rag.retriever.AiRagRetriever;
import com.hmdp.client.ContentCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DianPingAgentToolsTest {

    @Test
    void nearbyShopToolRejectsPageBeyondConfiguredMaximum() {
        ContentCatalog catalog = mock(ContentCatalog.class);
        DianPingAgentTools tools = new DianPingAgentTools(
                catalog, mock(AiRagRetriever.class), new AiAgentProperties());

        assertTrue(tools.searchNearbyShops(1, 121.0, 31.0, Integer.MAX_VALUE).isEmpty());

        verify(catalog, never()).nearby(1, Integer.MAX_VALUE, 121.0, 31.0);
    }

    @Test
    void nearbyShopToolPreservesPagesWithinConfiguredMaximum() {
        ContentCatalog catalog = mock(ContentCatalog.class);
        DianPingAgentTools tools = new DianPingAgentTools(
                catalog, mock(AiRagRetriever.class), new AiAgentProperties());

        tools.searchNearbyShops(1, 121.0, 31.0, 20);

        verify(catalog).nearby(1, 20, 121.0, 31.0);
    }

    @Test
    void nearbyShopToolDoesNotCallContentServiceForInvalidCoordinates() {
        ContentCatalog catalog = mock(ContentCatalog.class);
        DianPingAgentTools tools = new DianPingAgentTools(
                catalog, mock(AiRagRetriever.class), new AiAgentProperties());

        assertTrue(tools.searchNearbyShops(1, 181.0, 31.0, 1).isEmpty());
        assertTrue(tools.searchNearbyShops(1, 121.0, null, 1).isEmpty());
        assertTrue(tools.searchNearbyShops(1, Double.NaN, 31.0, 1).isEmpty());

        verifyNoInteractions(catalog);
    }
}
