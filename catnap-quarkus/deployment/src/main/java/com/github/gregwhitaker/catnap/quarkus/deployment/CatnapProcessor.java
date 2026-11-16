package com.github.gregwhitaker.catnap.quarkus.deployment;

import com.github.gregwhitaker.catnap.quarkus.CatnapQuarkusResponses;
import com.github.gregwhitaker.catnap.quarkus.CatnapRequestFilter;
import com.github.gregwhitaker.catnap.quarkus.CatnapResponseFilter;
import com.github.gregwhitaker.catnap.quarkus.CatnapServices;
import com.github.gregwhitaker.catnap.quarkus.CatnapStream;
import com.github.gregwhitaker.catnap.quarkus.CatnapStreamHandler;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AdditionalIndexedClassesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.resteasy.reactive.server.spi.MethodScannerBuildItem;
import java.util.List;
import java.util.Map;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.MethodInfo;
import org.jboss.resteasy.reactive.server.model.FixedHandlerChainCustomizer;
import org.jboss.resteasy.reactive.server.model.HandlerChainCustomizer;
import org.jboss.resteasy.reactive.server.processor.scanning.MethodScanner;

/** Registers Catnap at augmentation time, including REST provider discovery and CDI ownership. */
public class CatnapProcessor {
  @BuildStep
  FeatureBuildItem feature() {
    return new FeatureBuildItem("catnap");
  }

  @BuildStep
  AdditionalBeanBuildItem beans() {
    return AdditionalBeanBuildItem.builder()
        .setUnremovable()
        .addBeanClasses(
            CatnapServices.class,
            CatnapRequestFilter.class,
            CatnapResponseFilter.class,
            CatnapQuarkusResponses.class)
        .build();
  }

  @BuildStep
  AdditionalIndexedClassesBuildItem providers() {
    return new AdditionalIndexedClassesBuildItem(
        CatnapRequestFilter.class.getName(), CatnapResponseFilter.class.getName());
  }

  @BuildStep
  MethodScannerBuildItem streamResponses() {
    return new MethodScannerBuildItem(
        new MethodScanner() {
          @Override
          public List<HandlerChainCustomizer> scan(
              MethodInfo method, ClassInfo endpoint, Map<String, Object> context) {
            if (!isMethodSignatureAsync(method)) return List.of();
            return List.of(
                new FixedHandlerChainCustomizer(
                    new CatnapStreamHandler(), HandlerChainCustomizer.Phase.AFTER_METHOD_INVOKE));
          }

          @Override
          public boolean isMethodSignatureAsync(MethodInfo method) {
            return method.returnType().name().toString().equals(CatnapStream.class.getName());
          }
        });
  }
}
