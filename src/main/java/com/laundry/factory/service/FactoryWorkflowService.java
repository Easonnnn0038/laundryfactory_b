package com.laundry.factory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.factory.dto.ConfirmProcessRequest;
import com.laundry.factory.dto.ManualImportRequest;
import com.laundry.factory.dto.ScannerImportRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class FactoryWorkflowService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public FactoryWorkflowService(JdbcTemplate jdbc,ObjectMapper objectMapper){this.jdbc=jdbc;this.objectMapper=objectMapper;}

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> manualImport(ManualImportRequest request){return importOrder(request.orderNo(),request.deviceCode(),"手动输入订单号整单导入");}
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> scanImport(ScannerImportRequest request){
        String scan=request.scanCode().trim();
        List<String> orders=jdbc.query("SELECT order_no FROM factory_package WHERE package_no=?",(rs,n)->rs.getString(1),scan);
        return importOrder(orders.isEmpty()?scan:orders.get(0),request.deviceCode(),"扫码导入整单");
    }

    private Map<String,Object> importOrder(String rawOrderNo,String deviceCode,String remark){
        String orderNo=rawOrderNo.trim();
        List<Map<String,Object>> orders=jdbc.queryForList("SELECT id,order_no,total_count,urgent_flag,receive_time,status FROM laundry_order WHERE order_no=? FOR UPDATE",orderNo);
        if(orders.isEmpty())throw new IllegalArgumentException("未找到订单："+orderNo);
        Map<String,Object> order=orders.get(0);
        if(!"SENT_TO_FACTORY".equals(String.valueOf(order.get("status"))))throw new IllegalArgumentException("该订单尚未由门店打包送厂，当前状态："+order.get("status"));
        List<Map<String,Object>> packages=jdbc.queryForList("SELECT * FROM factory_package WHERE order_no=? ORDER BY package_seq FOR UPDATE",orderNo);
        if(packages.isEmpty())throw new IllegalArgumentException("订单缺少大件包装信息，请先在门店系统执行打包送厂");
        if(packages.stream().anyMatch(p->"FROZEN".equals(String.valueOf(p.get("status")))))throw new IllegalArgumentException("该订单存在冻结的大件，请联系客服处理");
        long orderId=number(order.get("id"));
        List<Map<String,Object>> items=jdbc.queryForList("""
                SELECT oi.id,oi.barcode,oi.category_group,fpi.package_id
                FROM order_item oi JOIN factory_package_item fpi ON fpi.order_item_id=oi.id
                JOIN factory_package fp ON fp.id=fpi.package_id
                WHERE oi.order_id=? AND fp.status='WAIT_FACTORY'
                  AND NOT EXISTS(SELECT 1 FROM factory_item_state fis WHERE fis.order_item_id=oi.id)
                ORDER BY oi.item_seq
                """,orderId);
        if(items.isEmpty())return orderDetail(orderNo);
        LocalDateTime receive=toLocalDateTime(order.get("receive_time"));
        LocalDateTime deadline=receive.plusHours(((Number)order.get("urgent_flag")).intValue()==1?48:96);
        LocalDateTime now=LocalDateTime.now();String device=normalizeDevice(deviceCode);
        for(Map<String,Object> item:items){
            long itemId=number(item.get("id")),packageId=number(item.get("package_id"));String barcode=String.valueOf(item.get("barcode"));
            boolean shoes="SHOES".equals(item.get("category_group"));String process=shoes?"SHOE_WASH":"PROCESSING";String status=shoes?"WAIT_SHOE_WASH":"PROCESSING";
            jdbc.update("""
                    INSERT INTO factory_item_state(order_item_id,barcode,package_id,current_process,status,deadline_time,last_operate_time,create_time,update_time)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """,itemId,barcode,packageId,process,status,deadline,now,now,now);
            writeRecord(itemId,barcode,packageId,"RECEIVE","COMPLETE","WAIT_RECEIVE",status,device,"RECEIVE",remark);
        }
        jdbc.update("UPDATE factory_package SET received_item_count=expected_item_count,status='RECEIVED',received_time=?,update_time=? WHERE order_no=? AND status='WAIT_FACTORY'",now,now,orderNo);
        jdbc.update("UPDATE factory_package_item SET scan_status='MATCHED',scan_time=? WHERE package_id IN(SELECT id FROM factory_package WHERE order_no=?)",now,orderNo);
        return orderDetail(orderNo);
    }

    public Map<String,Object> orderDetail(String orderNo){
        List<Map<String,Object>> orders=jdbc.queryForList("""
                SELECT id,order_no AS orderNo,total_count AS totalCount,urgent_flag AS urgentFlag,receive_time AS receiveTime,status AS storeStatus
                FROM laundry_order WHERE order_no=?
                """,orderNo.trim());
        if(orders.isEmpty())throw new IllegalArgumentException("未找到订单："+orderNo);
        Map<String,Object> result=new LinkedHashMap<>(orders.get(0));
        result.put("packages",jdbc.queryForList("SELECT id,package_no AS packageNo,source_batch_no AS sourceBatchNo,expected_item_count AS expectedItemCount,status FROM factory_package WHERE order_no=? ORDER BY package_seq",orderNo.trim()));
        List<Map<String,Object>> items=jdbc.queryForList("""
                SELECT oi.id,oi.barcode,oi.category_group AS categoryGroup,oi.category_name AS categoryName,oi.color,oi.brand,oi.special,
                       fis.current_process AS currentProcess,fis.status,fis.rework_count AS reworkCount,fis.deadline_time AS deadlineTime,
                       EXISTS(SELECT 1 FROM factory_process_record r WHERE r.order_item_id=oi.id AND r.process_code='WASH' AND r.action_code='RECORDED') AS washed,
                       EXISTS(SELECT 1 FROM factory_process_record r WHERE r.order_item_id=oi.id AND r.process_code='DRY' AND r.action_code='RECORDED') AS dried,
                       EXISTS(SELECT 1 FROM factory_process_record r WHERE r.order_item_id=oi.id AND r.process_code='IRON' AND r.action_code='RECORDED') AS ironed
                FROM order_item oi LEFT JOIN factory_item_state fis ON fis.order_item_id=oi.id WHERE oi.order_id=? ORDER BY oi.item_seq
                """,result.get("id"));
        result.put("items",items);result.put("currentProcess",aggregateProcess(items));return result;
    }

    public List<Map<String,Object>> waitingOrders(String process){
        String value=process.toUpperCase();String condition="QUALITY".equals(value)?"fis.current_process IN ('PROCESSING','QUALITY')":"fis.current_process=?";
        Object[] args="QUALITY".equals(value)?new Object[0]:new Object[]{value};
        return jdbc.queryForList("""
                SELECT lo.order_no AS orderNo,lo.total_count AS totalCount,lo.urgent_flag AS urgentFlag,MIN(fis.deadline_time) AS deadlineTime,COUNT(*) AS readyItemCount
                FROM factory_item_state fis JOIN order_item oi ON oi.id=fis.order_item_id JOIN laundry_order lo ON lo.id=oi.order_id
                WHERE %s GROUP BY lo.id,lo.order_no,lo.total_count,lo.urgent_flag ORDER BY lo.urgent_flag DESC,MIN(fis.deadline_time)
                """.formatted(condition),args);
    }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> confirm(ConfirmProcessRequest request){
        String action=request.process().toUpperCase();
        if(!Set.of("WASH","DRY","IRON","SHOE_WASH","QUALITY","PACK").contains(action))throw new IllegalArgumentException("不支持的操作："+action);
        Map<String,Object> detail=orderDetail(request.orderNo());
        @SuppressWarnings("unchecked") List<Map<String,Object>> all=(List<Map<String,Object>>)detail.get("items");
        if(all.isEmpty()||all.stream().allMatch(i->i.get("currentProcess")==null))throw new IllegalArgumentException("订单尚未到厂导入");
        String device=normalizeDevice(request.deviceCode());LocalDateTime now=LocalDateTime.now();
        if(Set.of("WASH","DRY","IRON").contains(action)){
            List<Map<String,Object>> clothes=all.stream().filter(i->"PROCESSING".equals(i.get("currentProcess"))&&!"SHOES".equals(i.get("categoryGroup"))).toList();
            if(clothes.isEmpty())throw new IllegalArgumentException("该订单没有可记录的普通衣物");
            for(Map<String,Object> item:clothes)writeRecord(number(item.get("id")),String.valueOf(item.get("barcode")),packageId(item),action,"RECORDED",String.valueOf(item.get("status")),String.valueOf(item.get("status")),device,action,request.remark());
            return orderDetail(request.orderNo());
        }
        if("SHOE_WASH".equals(action)){
            List<Map<String,Object>> shoes=all.stream().filter(i->"SHOES".equals(i.get("categoryGroup"))&&"SHOE_WASH".equals(i.get("currentProcess"))).toList();
            if(shoes.isEmpty())throw new IllegalArgumentException("该订单没有待清洗的鞋子");
            for(Map<String,Object> item:shoes)move(item,"SHOE_WASH","QUALITY","WAIT_QUALITY",device,now,request.remark());
            updatePackages(shoes,"PROCESSING",now);return orderDetail(request.orderNo());
        }
        if("QUALITY".equals(action))return quality(request,all,device,now);
        List<Map<String,Object>> pack=all.stream().filter(i->"PACK".equals(i.get("currentProcess"))).toList();
        if(pack.isEmpty())throw new IllegalArgumentException("该订单没有质检合格、可打包的物品");
        Set<Long> readyPackages=new LinkedHashSet<>();for(Map<String,Object> item:pack)readyPackages.add(packageId(item));
        for(Long packageId:readyPackages){
            Integer pending=jdbc.queryForObject("SELECT COUNT(*) FROM factory_item_state WHERE package_id=? AND current_process<>'PACK'",Integer.class,packageId);
            if(pending!=null&&pending>0)throw new IllegalArgumentException("同一大件内还有物品未通过质检，请全部完成后再整件打包");
        }
        for(Map<String,Object> item:pack)move(item,"PACK","RETURN","WAIT_RETURN",device,now,request.remark());
        updatePackages(pack,"PACK",now);return orderDetail(request.orderNo());
    }

    private Map<String,Object> quality(ConfirmProcessRequest request,List<Map<String,Object>> all,String device,LocalDateTime now){
        String result=request.qualityResult()==null?"":request.qualityResult().toUpperCase();
        if(!Set.of("PASSED","REWORK").contains(result))throw new IllegalArgumentException("请选择质检结果");
        List<Map<String,Object>> items=all.stream().filter(i->Set.of("PROCESSING","QUALITY").contains(String.valueOf(i.get("currentProcess")))).toList();
        if(items.isEmpty())throw new IllegalArgumentException("该订单没有待质检衣物");
        String photos=photoJson(request.photoPaths());
        for(Map<String,Object> item:items){
            long id=number(item.get("id"));boolean shoes="SHOES".equals(item.get("categoryGroup"));int rework=((Number)item.get("reworkCount")).intValue()+("REWORK".equals(result)?1:0);
            String next="PASSED".equals(result)?"PACK":shoes?"SHOE_WASH":"PROCESSING";String status="PASSED".equals(result)?"WAIT_PACK":shoes?"WAIT_SHOE_WASH":"PROCESSING";
            int changed=jdbc.update("UPDATE factory_item_state SET current_process=?,status=?,rework_count=?,last_operate_time=?,update_time=?,version=version+1 WHERE order_item_id=? AND current_process IN ('PROCESSING','QUALITY')",next,status,rework,now,now,id);
            if(changed!=1)throw new IllegalArgumentException("衣物状态已变化，请刷新后重试");
            jdbc.update("INSERT INTO factory_quality_record(order_item_id,barcode,result,rework_count,reason,photo_paths,device_code,operate_time) VALUES(?,?,?,?,?,?,?,?)",id,item.get("barcode"),result,rework,request.remark(),photos,device,now);
            writeRecord(id,String.valueOf(item.get("barcode")),packageId(item),"QUALITY","REWORK".equals(result)?"REWORK":"COMPLETE",String.valueOf(item.get("status")),status,device,"QUALITY",request.remark());
        }
        updatePackages(items,"PASSED".equals(result)?"QUALITY_PASSED":"PROCESSING",now);return orderDetail(request.orderNo());
    }

    private void move(Map<String,Object> item,String beforeProcess,String next,String status,String device,LocalDateTime now,String remark){
        long id=number(item.get("id"));int changed=jdbc.update("UPDATE factory_item_state SET current_process=?,status=?,last_operate_time=?,update_time=?,version=version+1 WHERE order_item_id=? AND current_process=?",next,status,now,now,id,beforeProcess);
        if(changed!=1)throw new IllegalArgumentException("衣物状态已变化，请刷新后重试");
        writeRecord(id,String.valueOf(item.get("barcode")),packageId(item),beforeProcess,"COMPLETE",String.valueOf(item.get("status")),status,device,beforeProcess,remark);
    }
    private void updatePackages(List<Map<String,Object>> items,String requested,LocalDateTime now){
        Set<Long> ids=new LinkedHashSet<>();for(Map<String,Object> item:items)ids.add(packageId(item));
        for(Long id:ids){String status=requested;if("PACK".equals(requested)){Integer pending=jdbc.queryForObject("SELECT COUNT(*) FROM factory_item_state WHERE package_id=? AND current_process<>'RETURN'",Integer.class,id);status=pending!=null&&pending==0?"PACKED":"PROCESSING";}
            jdbc.update("UPDATE factory_package SET status=?,packed_time=IF(?='PACKED',?,packed_time),update_time=? WHERE id=?",status,status,now,now,id);}
    }
    private Long packageId(Map<String,Object> item){return jdbc.queryForObject("SELECT package_id FROM factory_item_state WHERE order_item_id=?",Long.class,item.get("id"));}
    private String photoJson(List<String> paths){List<String> safe=paths==null?List.of():paths.stream().filter(Objects::nonNull).map(String::trim).filter(p->p.matches("\\d{8}/[A-Za-z0-9-]+\\.(jpg|jpeg|png)")).distinct().limit(9).toList();if(paths!=null&&safe.size()!=paths.size())throw new IllegalArgumentException("质检照片信息不正确");try{return objectMapper.writeValueAsString(safe);}catch(JsonProcessingException e){throw new IllegalArgumentException("质检照片信息不正确");}}
    private void writeRecord(Long itemId,String barcode,Long packageId,String process,String action,String before,String after,String device,String station,String reason){jdbc.update("INSERT INTO factory_process_record(event_id,order_item_id,barcode,package_id,process_code,action_code,before_status,after_status,device_code,station_code,reason,operate_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),itemId,barcode,packageId,process,action,before,after,device,station,reason,LocalDateTime.now());}
    private String aggregateProcess(List<Map<String,Object>> items){if(items.isEmpty()||items.get(0).get("currentProcess")==null)return "WAIT_IMPORT";String first=String.valueOf(items.get(0).get("currentProcess"));return items.stream().allMatch(i->first.equals(String.valueOf(i.get("currentProcess"))))?first:"MIXED";}
    private String normalizeDevice(String value){return value==null||value.isBlank()?"MANUAL-STATION":value.trim();}
    private LocalDateTime toLocalDateTime(Object value){if(value instanceof LocalDateTime v)return v;if(value instanceof Timestamp v)return v.toLocalDateTime();throw new IllegalArgumentException("订单收衣时间格式异常");}
    private static long number(Object value){return ((Number)value).longValue();}
}
