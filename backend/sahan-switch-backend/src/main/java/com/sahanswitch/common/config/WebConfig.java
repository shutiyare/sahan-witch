package com.sahanswitch.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

/**
 * Task 3: serialize {@code Page<T>} through Spring Data's stable DTO form
 * ({@code content} + {@code page{size, number, totalElements, totalPages}}) instead of the raw
 * {@code PageImpl} internals, whose JSON shape is not guaranteed across versions.
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class WebConfig {
}
