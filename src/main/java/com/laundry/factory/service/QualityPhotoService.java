package com.laundry.factory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class QualityPhotoService {
    private static final Pattern PHOTO_PATH=Pattern.compile("\\d{8}/[A-Za-z0-9-]+\\.(jpg|jpeg|png)");
    private final Path root;
    private final JdbcTemplate jdbc;
    private final Duration orphanRetention;
    public QualityPhotoService(@Value("${factory.quality-photo-dir:./uploads/quality}") String directory,
                               @Value("${factory.quality-photo-orphan-hours:24}") long orphanHours,
                               JdbcTemplate jdbc){
        if(orphanHours<1)throw new IllegalArgumentException("质检照片孤儿保留时间至少为 1 小时");
        root=Path.of(directory).toAbsolutePath().normalize();this.jdbc=jdbc;orphanRetention=Duration.ofHours(orphanHours);
        try{Files.createDirectories(root);}catch(Exception e){throw new IllegalStateException("无法创建质检照片目录",e);}
    }
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
        try{Path file=photoFile(day,name).toRealPath();if(!file.startsWith(root.toRealPath())||!Files.isRegularFile(file))throw new IllegalArgumentException("照片不存在");return new UrlResource(file.toUri());}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("照片读取失败");}
    }
    public void delete(String day,String name){
        Path file=photoFile(day,name);
        if(isReferenced(day+"/"+name))throw new IllegalArgumentException("照片已用于质检记录，不能删除");
        try{if(!Files.deleteIfExists(file))throw new IllegalArgumentException("照片不存在");}
        catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalStateException("质检照片删除失败",e);}
    }
    @Scheduled(cron="${factory.quality-photo-cleanup-cron:0 20 4 * * *}")
    public void cleanOrphans(){
        Instant cutoff=Instant.now().minus(orphanRetention);
        try(var files=Files.walk(root,2)){
            for(Path file:files.filter(path->Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)).toList()){
                String relative=root.relativize(file).toString().replace('\\','/');
                if(!PHOTO_PATH.matcher(relative).matches()||!Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)||isReferenced(relative))continue;
                Files.deleteIfExists(file);
            }
        }catch(Exception e){throw new IllegalStateException("质检孤儿照片清理失败",e);}
    }
    private boolean isReferenced(String path){
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM factory_quality_record WHERE JSON_CONTAINS(photo_paths, JSON_QUOTE(?))",Integer.class,path);
        return count!=null&&count>0;
    }
    private Path photoFile(String day,String name){
        if(!day.matches("\\d{8}")||!name.matches("[A-Za-z0-9-]+\\.(jpg|jpeg|png)"))throw new IllegalArgumentException("照片路径不正确");
        return root.resolve(day).resolve(name);
    }
}
