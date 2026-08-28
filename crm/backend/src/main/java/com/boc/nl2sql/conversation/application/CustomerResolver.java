package com.boc.nl2sql.conversation.application;

import com.boc.nl2sql.authorization.application.DataScopePolicy;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import com.boc.nl2sql.nl2sql.domain.ClarificationQuestion;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** 姓名仅在此受控入口定位；不会把原始姓名、候选明细交给外部模型。 */
@Service
public class CustomerResolver {
    private static final Pattern ID=Pattern.compile("(?i)(?<![A-Za-z0-9])C[0-9]{8}(?![A-Za-z0-9])");
    private static final Pattern TITLE=Pattern.compile("([赵钱孙李周吴郑王冯陈沈韩杨朱秦许何吕张曹华金魏姜谢邹苏潘葛范彭马方任袁柳史唐薛雷贺罗郝常于傅齐康伍余顾孟黄尹姚邵汪毛成戴宋熊舒董梁杜贾江颜郭梅林钟徐邱高夏蔡田胡万柯管卢莫丁邓洪包石崔龚程陆段侯全宫宁白赖谭冉牛边温庄柴阎向廖耿武刘龙叶黎乔申欧司上慕][\\p{IsHan}]{0,2}?)(先生|女士|小姐|老师|经理)");
    private static final Pattern NAME=Pattern.compile("(?:查找一下|查找|查询|查一下|查看|看看|查|客户姓名[为是：:]?|姓名[为是：:]?|客户定位信息：)\\s*(?:客户)?([\\p{IsHan}]{2,4}?)(?=的|[，。\\s]|资产|交易|产品|$)");
    private static final String SURNAMES="赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗方俞任袁柳鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于傅皮卞齐康伍余元卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧计伏成戴谈宋庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林钟徐邱骆高夏蔡田胡凌霍虞万支柯管卢莫房裘缪干解应宗丁宣邓单洪包左石崔吉龚程嵇邢裴陆荣翁荀甄曲封芮储靳段富巫乌焦巴弓牧山谷车侯全秋宫宁仇栾甘厉戎祖武符刘景詹束龙叶幸司韶郜黎蓟薄印宿白怀蒲邰从鄂索咸籍赖卓蔺屠蒙池乔阴胥能苍双闻莘党翟谭贡劳姬申扶堵冉宰郦雍郤璩桑桂濮牛寿通边扈燕冀郏浦尚农温庄晏柴瞿阎连茹习艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东欧阳上官司马诸葛夏侯皇甫尉迟公孙慕容令狐";
    private final NamedParameterJdbcTemplate jdbc;
    private final DataScopePolicy scope;
    public CustomerResolver(NamedParameterJdbcTemplate jdbc,DataScopePolicy scope){this.jdbc=jdbc;this.scope=scope;}
    public record Candidate(String customerId,String name,String branchId,String mobile){}
    public record Mention(String text,String name,boolean surname){}
    public boolean explicitIdentity(String text){return ID.matcher(text).find() || mention(text)!=null;}
    public Mention mention(String text){
        if(text.contains("，客户定位信息：")){
            String hint=text.substring(text.lastIndexOf("，客户定位信息：")+"，客户定位信息：".length()).trim();
            if(hint.matches("[\\p{IsHan}]{2,4}"))return new Mention(hint,hint,false);
        }
        if(text.matches("(?s).*(姓[\\p{IsHan}]{1,2}的?客户|[\\p{IsHan}]{1,2}姓客户|所有.*客户|各.*客户).*")&&!text.contains("先生")&&!text.contains("女士"))return null;
        var titled=TITLE.matcher(text);
        if(titled.find()){
            String value=titled.group(1).replaceFirst("^(一下|客户|查|看|找)","");
            if(value.length()>0 && SURNAMES.contains(value.substring(0,1)))return new Mention(value+titled.group(2),value,value.length()==1||Set.of("欧阳","上官","司马","诸葛","慕容").contains(value));
        }
        Mention found=null;var names=NAME.matcher(text);
        while(names.find()) {String n=names.group(1);if(SURNAMES.contains(n.substring(0,1))&&!Set.of("当前","所有","本月","今年","最近","昨天").contains(n))found=new Mention(n,n,false);}
        return found;
    }
    public String redact(String text){var m=mention(text);return m==null?text:text.replace(m.text(),m.surname()?m.text():mask(m.name()));}
    public static String mask(String name){return com.boc.nl2sql.common.privacy.CustomerMasking.name(name);}
    public List<Candidate> find(CurrentUser user,String id,Mention mention,String suffix){
        Map<String,Object> params=new LinkedHashMap<>();String condition=scope.condition("c",user,params)+" AND c.status_code='ACTIVE'";
        if(id!=null){condition+=" AND c.customer_id=:customerId";params.put("customerId",id);}
        if(mention!=null){
            condition+=mention.surname()?" AND (c.customer_name LIKE :customerName OR (c.customer_name IS NULL AND c.customer_name_masked LIKE :customerName))":" AND c.customer_name=:customerName";
            params.put("customerName",mention.name()+(mention.surname()?"%":""));
        }else if(id==null)return List.of();
        if(suffix!=null){condition+=" AND RIGHT(c.mobile_masked,4)=:suffix";params.put("suffix",suffix);}
        return jdbc.query("SELECT c.customer_id,c.customer_name,c.customer_name_masked,c.branch_id,c.mobile_masked FROM dim_customer c WHERE "+condition+" ORDER BY c.customer_id LIMIT 11",params,
                (rs,n)->new Candidate(rs.getString(1),mask(rs.getString(2)==null?rs.getString(3):rs.getString(2)),rs.getString(4),com.boc.nl2sql.common.privacy.CustomerMasking.mobile(rs.getString(5))));
    }
    public Optional<ClarificationQuestion> inspect(QueryTaskEntity task,CurrentUser user){
        if(task.getResolvedCustomerId()!=null){
            if(find(user,task.getResolvedCustomerId(),null,null).isEmpty())throw new BusinessException(404001,"当前可查询范围内未找到该客户，请重新选择");
            return Optional.empty();
        }
        String text=task.getMergedQueryText();var id=ID.matcher(text);Mention mention=mention(text);
        if(id.find()){
            String value=id.group().toUpperCase(Locale.ROOT);var matches=find(user,value,mention,null);
            if(matches.size()==1){task.setResolvedCustomerId(value);task.setMergedQueryText(safeText(text,mention));return Optional.empty();}
            return Optional.of(question("CUSTOMER_NOT_FOUND","当前可查询范围内未找到该客户，请核对客户编号。",List.of()));
        }
        if(mention==null){
            if(text.matches("(?s).*(他的|她的|这位客户|该客户|已确认客户).*"))return Optional.of(question("CUSTOMER_IDENTITY","尚未确定具体客户，请提供客户编号或虚构完整姓名。",List.of()));
            return Optional.empty();
        }
        String suffix=null;var tail=Pattern.compile("(?:尾号|后四位|客户定位信息：)\\s*(\\d{4})\\b").matcher(text);if(tail.find())suffix=tail.group(1);
        var candidates=find(user,null,mention,suffix);
        if(candidates.isEmpty())return Optional.of(question("CUSTOMER_NOT_FOUND","当前可查询范围内未找到匹配客户，请核对客户编号或虚构完整姓名。",List.of()));
        if(candidates.size()>10)return Optional.of(question("CUSTOMER_IDENTITY","匹配客户较多，请补充客户编号、虚构完整姓名或手机号后四位。",List.of()));
        return Optional.of(question("CUSTOMER_SELECTION","请确认要查询的客户；选择前不会查询资产或交易信息。",candidates));
    }
    public void answer(QueryTaskEntity task,CurrentUser user,ClarificationQuestion question,String answer){
        var id=ID.matcher(answer);
        if(id.find()){
            String value=id.group().toUpperCase(Locale.ROOT);
            if("CUSTOMER_SELECTION".equals(question.type()) && question.candidates().stream().noneMatch(c->c.customerId().equals(value)))
                throw new BusinessException(409002,"客户不在本次候选列表中，请重新补充定位信息");
            if(find(user,value,null,null).isEmpty())throw new BusinessException(404001,"当前可查询范围内未找到该客户");
            task.setResolvedCustomerId(value);task.setMergedQueryText(safeText(task.getMergedQueryText(),mention(task.getMergedQueryText())));
        }else{
            // 新定位信息替换旧补充，防止姓名与尾号无限追加。
            String base=task.getMergedQueryText().split("，客户定位信息：",2)[0];
            task.setMergedQueryText(base+"，客户定位信息："+answer);
        }
    }
    public void answer(QueryTaskEntity task,CurrentUser user,ClarificationQuestion question,String answer,String identityType){
        if("CUSTOMER_SELECTION".equals(question.type())) {
            if(!answer.matches("(?i)C[0-9]{8}"))throw new BusinessException(400006,"请从本轮候选客户中选择");
        } else {
            if(identityType==null)throw new BusinessException(400006,"请先选择客户编号、客户虚拟姓名或手机号后四位");
            boolean valid=switch(identityType){
                case "CUSTOMER_ID" -> answer.matches("(?i)C[0-9]{8}");
                case "CUSTOMER_NAME" -> answer.matches("[\\p{IsHan}]{2,4}");
                case "MOBILE_SUFFIX" -> answer.matches("[0-9]{4}");
                default -> false;
            };
            if(!valid)throw new BusinessException(400006,"输入内容与所选身份类型不符：编号为C加8位数字，虚拟姓名为2至4个汉字，手机尾号为4位数字");
            if("MOBILE_SUFFIX".equals(identityType) && mention(task.getMergedQueryText().split("，客户定位信息：",2)[0])==null)
                throw new BusinessException(400006,"仅手机号后四位不足以定位，请改用客户编号或虚拟姓名");
        }
        answer(task,user,question,answer);
    }
    private String safeText(String text,Mention mention){
        String base=text.split("，客户定位信息：",2)[0];Mention original=mention(base);
        return original==null?base:base.replace(original.text(),"已确认客户");
    }
    private ClarificationQuestion question(String type,String text,List<Candidate> candidates){
        return new ClarificationQuestion(UUID.randomUUID().toString(),type,text,List.of(),Map.of("查询对象","单个客户，需核验身份"),candidates);
    }
}
