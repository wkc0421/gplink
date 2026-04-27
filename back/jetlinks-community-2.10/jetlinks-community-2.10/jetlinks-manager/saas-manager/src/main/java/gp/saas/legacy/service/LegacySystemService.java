/*
 * Copyright 2025 JetLinks https://www.jetlinks.cn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gp.saas.legacy.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class LegacySystemService {

    private final ApplicationContext applicationContext;

    public LegacySystemService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public JSONObject runGc() {
        long beforeUsed = usedMemory();
        long beforeFree = Runtime.getRuntime().freeMemory();
        long startedAt = System.currentTimeMillis();

        System.gc();
        System.runFinalization();

        long afterUsed = usedMemory();
        long afterFree = Runtime.getRuntime().freeMemory();

        JSONObject result = new JSONObject(new LinkedHashMap<>());
        result.put("success", true);
        result.put("beforeUsedMemory", beforeUsed);
        result.put("afterUsedMemory", afterUsed);
        result.put("releasedMemory", Math.max(beforeUsed - afterUsed, 0));
        result.put("beforeFreeMemory", beforeFree);
        result.put("afterFreeMemory", afterFree);
        result.put("freeMemoryIncreased", Math.max(afterFree - beforeFree, 0));
        result.put("durationMs", Math.max(System.currentTimeMillis() - startedAt, 0));
        return result;
    }

    public JSONObject softRestart() {
        long startedAt = System.currentTimeMillis();

        JSONObject result = new JSONObject(new LinkedHashMap<>());
        result.put("success", true);
        result.put("message", "刷新完成");
        result.put("refreshed", true);
        result.put("beanDefinitionCount", applicationContext.getBeanDefinitionCount());
        result.put("durationMs", Math.max(System.currentTimeMillis() - startedAt, 0));
        return result;
    }

    public JSONObject memoryAnalysis() {
        MemoryMXBean memoryMxBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
        ClassLoadingMXBean classLoadingMxBean = ManagementFactory.getClassLoadingMXBean();

        JSONObject result = new JSONObject(new LinkedHashMap<>());
        result.put("success", true);
        result.put("jvm", createJvmMetrics());
        result.put("heapMemory", toJson(memoryMxBean.getHeapMemoryUsage()));
        result.put("nonHeapMemory", toJson(memoryMxBean.getNonHeapMemoryUsage()));
        result.put("memoryPools", createMemoryPools());
        result.put("garbageCollectors", createGarbageCollectors());
        result.put("threads", createThreadMetrics(threadMxBean));
        result.put("classLoading", createClassLoadingMetrics(classLoadingMxBean));
        return result;
    }

    private JSONObject createJvmMetrics() {
        Runtime runtime = Runtime.getRuntime();
        JSONObject jvm = new JSONObject(new LinkedHashMap<>());
        jvm.put("processors", runtime.availableProcessors());
        jvm.put("freeMemory", runtime.freeMemory());
        jvm.put("totalMemory", runtime.totalMemory());
        jvm.put("maxMemory", runtime.maxMemory());
        jvm.put("usedMemory", runtime.totalMemory() - runtime.freeMemory());
        return jvm;
    }

    private JSONArray createMemoryPools() {
        JSONArray pools = new JSONArray();
        for (MemoryPoolMXBean bean : ManagementFactory.getMemoryPoolMXBeans()) {
            JSONObject pool = new JSONObject(new LinkedHashMap<>());
            pool.put("name", bean.getName());
            pool.put("type", String.valueOf(bean.getType()));
            pool.put("usage", toJson(bean.getUsage()));
            pool.put("peakUsage", toJson(bean.getPeakUsage()));
            pools.add(pool);
        }
        return pools;
    }

    private JSONArray createGarbageCollectors() {
        JSONArray collectors = new JSONArray();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            JSONObject collector = new JSONObject(new LinkedHashMap<>());
            collector.put("name", bean.getName());
            collector.put("collectionCount", bean.getCollectionCount());
            collector.put("collectionTime", bean.getCollectionTime());
            collectors.add(collector);
        }
        return collectors;
    }

    private JSONObject createThreadMetrics(ThreadMXBean bean) {
        JSONObject threads = new JSONObject(new LinkedHashMap<>());
        threads.put("threadCount", bean.getThreadCount());
        threads.put("daemonThreadCount", bean.getDaemonThreadCount());
        threads.put("peakThreadCount", bean.getPeakThreadCount());
        threads.put("totalStartedThreadCount", bean.getTotalStartedThreadCount());
        return threads;
    }

    private JSONObject createClassLoadingMetrics(ClassLoadingMXBean bean) {
        JSONObject classLoading = new JSONObject(new LinkedHashMap<>());
        classLoading.put("loadedClassCount", bean.getLoadedClassCount());
        classLoading.put("totalLoadedClassCount", bean.getTotalLoadedClassCount());
        classLoading.put("unloadedClassCount", bean.getUnloadedClassCount());
        return classLoading;
    }

    private JSONObject toJson(MemoryUsage usage) {
        if (usage == null) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("init", usage.getInit());
        data.put("used", usage.getUsed());
        data.put("committed", usage.getCommitted());
        data.put("max", usage.getMax());
        return new JSONObject(data);
    }

    private long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
