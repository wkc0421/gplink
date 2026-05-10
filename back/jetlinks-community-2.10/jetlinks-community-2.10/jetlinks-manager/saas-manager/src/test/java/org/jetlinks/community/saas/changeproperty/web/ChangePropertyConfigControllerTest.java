package org.jetlinks.community.saas.changeproperty.web;

import org.junit.jupiter.api.Test;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangePropertyConfigControllerTest {

    @Test
    void shouldExposeAllConfiguredEndpoints() throws Exception {
        assertPost("query", "/_query");
        assertGet("get", "/{id:.+}");
        assertPost("upsert");
        assertPut("update", "/{id:.+}");
        assertDelete("delete", "/{id:.+}");
        assertPost("batch", "/batch");
        assertPost("byProduct", "/by-product");
        assertPost("batchDelete", "/batch-delete");
        assertPost("deleteByQuery", "/delete-by-query");
        assertPost("rebuildCache", "/cache");
        assertPost("importMultipart", "/import");
        assertPost("importByFileUrl", "/import");
        assertGet("template", "/template.{format}");
    }

    @Test
    void shouldNotBypassAuthorization() {
        Authorize authorize = ChangePropertyConfigController.class.getAnnotation(Authorize.class);
        assertTrue(authorize == null || !authorize.ignore());
    }

    private static void assertPost(String methodName, String... paths) throws Exception {
        PostMapping mapping = method(methodName).getAnnotation(PostMapping.class);
        assertNotNull(mapping, methodName);
        assertArrayEquals(paths, values(mapping.value(), mapping.path()));
    }

    private static void assertGet(String methodName, String... paths) throws Exception {
        GetMapping mapping = method(methodName).getAnnotation(GetMapping.class);
        assertNotNull(mapping, methodName);
        assertArrayEquals(paths, values(mapping.value(), mapping.path()));
    }

    private static void assertPut(String methodName, String... paths) throws Exception {
        PutMapping mapping = method(methodName).getAnnotation(PutMapping.class);
        assertNotNull(mapping, methodName);
        assertArrayEquals(paths, values(mapping.value(), mapping.path()));
    }

    private static void assertDelete(String methodName, String... paths) throws Exception {
        DeleteMapping mapping = method(methodName).getAnnotation(DeleteMapping.class);
        assertNotNull(mapping, methodName);
        assertArrayEquals(paths, values(mapping.value(), mapping.path()));
    }

    private static Method method(String name) {
        return Arrays
            .stream(ChangePropertyConfigController.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(name))
            .findFirst()
            .orElseThrow();
    }

    private static String[] values(String[] value, String[] path) {
        return value.length == 0 ? path : value;
    }
}
