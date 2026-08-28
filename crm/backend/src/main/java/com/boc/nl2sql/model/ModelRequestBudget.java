package com.boc.nl2sql.model;

import com.boc.nl2sql.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** 可选的跨进程评测额度。每次HTTP尝试前占用一次；失败不退回，重启不重置。 */
@Component
public class ModelRequestBudget {
    private final String file; private final int limit;
    public ModelRequestBudget(@Value("${app.model.request-budget-file:}") String file,@Value("${app.model.request-budget:50}") int limit){this.file=file;this.limit=limit;}
    public synchronized void acquire(){
        if(file.isBlank())return;
        try(var channel=FileChannel.open(Path.of(file),StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);
            var lock=channel.lock()){
            var buffer=ByteBuffer.allocate(64);channel.read(buffer);buffer.flip();
            String saved=StandardCharsets.UTF_8.decode(buffer).toString().trim();
            int count=saved.isEmpty()?0:Integer.parseInt(saved);
            if(count<0 || count>=limit)throw new BusinessException(429101,"真实模型评测请求额度已用尽，未发起本次请求");
            channel.position(0);channel.write(StandardCharsets.UTF_8.encode(Integer.toString(count+1)));channel.truncate(channel.position());channel.force(true);
        }catch(BusinessException e){throw e;}catch(Exception e){throw new BusinessException(429102,"模型请求计数文件不可用，已停止调用以保护额度");}
    }
}
