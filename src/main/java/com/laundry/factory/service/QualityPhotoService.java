package com.laundry.factory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class QualityPhotoService {
    private final Path root;
    public QualityPhotoService(@Value("${factory.quality-photo-dir:./uploads/quality}") String directory){root=Path.of(directory).toAbsolutePath().normalize();try{Files.createDirectories(root);}catch(Exception e){throw new IllegalStateException("无法创建质检照片目录",e);}}
    public Map<String,Object> save(MultipartFile file){
        if(file==null||file.isEmpty())throw new IllegalArgumentException("请选择照片");
        if(file.getSize()>10*1024*1024)throw new IllegalArgumentException("单张照片不能超过10MB");
        String type=file.getContentType()==null?"":file.getContentType().toLowerCase(Locale.ROOT);
        if(!type.matches("image/(jpeg|png)"))throw new IllegalArgumentException("仅支持 JPG 或 PNG 图片");
        try(InputStream input=file.getInputStream()){
            var image=ImageIO.read(input);if(image==null)throw new IllegalArgumentException("文件不是有效图片");
            if((long)image.getWidth()*image.getHeight()>40_000_000L)throw new IllegalArgumentException("图片像素过大");
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("图片读取失败");}
        String ext="image/png".equals(type)?"png":"jpg";
        String day=LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);String name=UUID.randomUUID()+"."+ext;Path dir=root.resolve(day);
        try{Files.createDirectories(dir);file.transferTo(dir.resolve(name));}catch(Exception e){throw new IllegalStateException("质检照片保存失败",e);}
        return Map.of("path",day+"/"+name,"url","/api/factory/quality-photos/"+day+"/"+name);
    }
    public Resource read(String day,String name){
        if(!day.matches("\\d{8}")||!name.matches("[A-Za-z0-9-]+\\.(jpg|jpeg|png)"))throw new IllegalArgumentException("照片路径不正确");
        try{Path file=root.resolve(day).resolve(name).normalize();if(!file.startsWith(root)||!Files.isRegularFile(file))throw new IllegalArgumentException("照片不存在");return new UrlResource(file.toUri());}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("照片读取失败");}
    }
}
