package com.laundry.factory.controller;

import com.laundry.factory.common.Result;
import com.laundry.factory.service.QualityPhotoService;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
@RequestMapping("/api/factory/quality-photos")
public class QualityPhotoController {
    private final QualityPhotoService service;
    public QualityPhotoController(QualityPhotoService service){this.service=service;}
    @PostMapping public Result<Map<String,Object>> upload(@RequestParam("file") MultipartFile file){return Result.success(service.save(file));}
    @GetMapping("/{day}/{name:.+}") public ResponseEntity<Resource> read(@PathVariable String day,@PathVariable String name){Resource resource=service.read(day,name);String type=name.endsWith(".png")?"image/png":"image/jpeg";return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header(HttpHeaders.CACHE_CONTROL,"private, max-age=3600").body(resource);}
}
