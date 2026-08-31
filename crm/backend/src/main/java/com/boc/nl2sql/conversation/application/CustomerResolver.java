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
    public static final String FILTER_NAME="CUSTOMER_NAME";
    public static final String FILTER_ID="CUSTOMER_ID";
    public static final String FILTER_MOBILE_SUFFIX="MOBILE_SUFFIX";
    private static final Pattern ID=Pattern.compile("(?i)(?<![A-Za-z0-9])C[0-9]{8}(?![A-Za-z0-9])");
    private static final Pattern TITLE=Pattern.compile("([赵钱孙李周吴郑王冯陈沈韩杨朱秦许何吕张曹华金魏姜谢邹苏潘葛范彭马方任袁柳史唐薛雷贺罗郝常于傅齐康伍余顾孟黄尹姚邵汪毛成戴宋熊舒董梁杜贾江颜郭梅林钟徐邱高夏蔡田胡万柯管卢莫丁邓洪包石崔龚程陆段侯全宫宁白赖谭冉牛边温庄柴阎向廖耿武刘龙叶黎乔申欧司上慕][\\p{IsHan}]{0,2}?)(先生|女士|小姐|老师|经理)");
    private static final Pattern NAME=Pattern.compile("(?:查找一下|查找|查询|查一下|查看|看看|查|客户姓名[为是：:]?|姓名[为是：:]?|客户定位信息：)\\s*(?:客户)?([\\p{IsHan}]{2,4}?)(?=的|[，。\\s]|资产|交易|产品|$)");
    private static final Pattern MOBILE_SUFFIX=Pattern.compile("(?:手机号?|电话)?(?:后四位|尾号)\\s*(?:为|是|[:：])?\\s*([0-9]{4})");
    private static final String SURNAMES="赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗方俞任袁柳鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于傅皮卞齐康伍余元卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧计伏成戴谈宋庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林钟徐邱骆高夏蔡田胡凌霍虞万支柯管卢莫房裘缪干解应宗丁宣邓单洪包左石崔吉龚程嵇邢裴陆荣翁荀甄曲封芮储靳段富巫乌焦巴弓牧山谷车侯全秋宫宁仇栾甘厉戎祖武符刘景詹束龙叶幸司韶郜黎蓟薄印宿白怀蒲邰从鄂索咸籍赖卓蔺屠蒙池乔阴胥能苍双闻莘党翟谭贡劳姬申扶堵冉宰郦雍郤璩桑桂濮牛寿通边扈燕冀郏浦尚农温庄晏柴瞿阎连茹习艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东欧阳上官司马诸葛夏侯皇甫尉迟公孙慕容令狐";
    /** 提取出的"姓名"若包含业务词，视为问题文本而非人名，避免"姓名和资产/姓名和电话"被当成客户。 */
    private static final Pattern NOT_A_NAME=Pattern.compile("资产|交易|持有|持仓|产品|金额|记录|情况|信息|分布|排名|名单|流水|余额|收益|明细|统计|电话|手机|号码|地址|邮箱|次数");
    private final NamedParameterJdbcTemplate jdbc;
    private final DataScopePolicy scope;
    private final tools.jackson.databind.ObjectMapper json;
    public CustomerResolver(NamedParameterJdbcTemplate jdbc,DataScopePolicy scope,tools.jackson.databind.ObjectMapper json){this.jdbc=jdbc;this.scope=scope;this.json=json;}
    public record Candidate(String customerId,String name,String branchId,String mobile){}
    public record Mention(String text,String name,boolean surname){}

    public boolean explicitIdentity(String text){return ID.matcher(text).find() || mention(text)!=null;}

    /** 检测多个不同客户称谓/姓名（编号不算）：平台不支持一次比较多位客户。 */
    public boolean multiplePersons(String text){
        if(text==null || ID.matcher(text).find()) return false;
        Set<String> persons=new LinkedHashSet<>();
        var titled=TITLE.matcher(text);
        while(titled.find()){
            String value=titled.group(1).replaceFirst("^(一下|客户|查|看|找)","");
            if(value.length()>0 && SURNAMES.contains(value.substring(0,1))) persons.add(value+titled.group(2));
        }
        var names=NAME.matcher(text);
        while(names.find()){
            String n=names.group(1);
            if(isPlausibleName(n)) persons.add(n);
        }
        return persons.size()>=2;
    }

    /** 姓名合理性：姓氏开头、非业务词、非指代代词，且"姓名和X"语境下不以连词开头（和资产/与电话不是人名）。 */
    private boolean isPlausibleName(String n){
        if(n==null || n.length()<1 || !SURNAMES.contains(n.substring(0,1)))return false;
        if(NOT_A_NAME.matcher(n).find())return false;
        if(Set.of("当前","所有","本月","今年","最近","昨天").contains(n))return false;
        if(n.length()>1 && "和与及或".contains(n.substring(0,1)))return false;
        return true;
    }

    public Mention mention(String text){
        if(text==null) return null;
        if(text.contains("，客户定位信息：")){
            String hint=text.substring(text.lastIndexOf("，客户定位信息：")+"，客户定位信息：".length()).trim();
            if(hint.matches("[\\p{IsHan}]{2,4}") && !NOT_A_NAME.matcher(hint).find())return new Mention(hint,hint,false);
        }
        if(text.matches("(?s).*(姓[\\p{IsHan}]{1,2}的?客户|[\\p{IsHan}]{1,2}姓客户|所有.*客户|各.*客户).*")&&!text.contains("先生")&&!text.contains("女士"))return null;
        var titled=TITLE.matcher(text);
        if(titled.find()){
            String value=titled.group(1).replaceFirst("^(一下|客户|查|看|找)","");
            if(value.length()>0 && SURNAMES.contains(value.substring(0,1)))return new Mention(value+titled.group(2),value,value.length()==1||Set.of("欧阳","上官","司马","诸葛","慕容").contains(value));
        }
        Mention found=null;var names=NAME.matcher(text);
        while(names.find()) {
            String n=names.group(1);
            if(isPlausibleName(n))found=new Mention(n,n,false);
        }
        return found;
    }
    public String redact(String text){
        var m=mention(text);
        if(m==null)return text;
        String replacement=m.surname()?m.text():mask(m.name());
        // 兜底：误捕获产生的"和**"类掩码比原文更困惑，此时保留原文。
        if(replacement.length()>1 && "和与及或".contains(replacement.substring(0,1)))return text;
        return text.replace(m.text(),replacement);
    }
    public static String mask(String name){return com.boc.nl2sql.common.privacy.CustomerMasking.name(name);}

    public List<Candidate> find(CurrentUser user,String id,Mention mention,String suffix){
        Map<String,Object> params=new LinkedHashMap<>();String condition=scope.condition("c",user,params)+" AND c.status_code='ACTIVE'";
        if(id!=null){condition+=" AND c.customer_id=:customerId";params.put("customerId",id);}
        if(mention!=null){
            condition+=mention.surname()?" AND (c.customer_name LIKE :customerName OR (c.customer_name IS NULL AND c.customer_name_masked LIKE :customerName))":" AND c.customer_name=:customerName";
            params.put("customerName",mention.name()+(mention.surname()?"%":""));
        }else if(id==null && suffix==null)return List.of();
        if(suffix!=null){condition+=" AND RIGHT(c.mobile_masked,4)=:suffix";params.put("suffix",suffix);}
        return jdbc.query("SELECT c.customer_id,c.customer_name,c.customer_name_masked,c.branch_id,c.mobile_masked FROM dim_customer c WHERE "+condition+" ORDER BY c.customer_id LIMIT 11",params,
                (rs,n)->new Candidate(rs.getString(1),mask(rs.getString(2)==null?rs.getString(3):rs.getString(2)),rs.getString(4),com.boc.nl2sql.common.privacy.CustomerMasking.mobile(rs.getString(5))));
    }

    /** v1.5 客户检索范围由服务端当前澄清任务生成，前端只能提交附加筛选。 */
    public record SearchResult(int total,int page,int size,List<Candidate> items){}
    public record SearchScope(String customerName,boolean surname,String customerId,String mobileSuffix,Set<String> allowedFilters){
        public SearchScope {
            customerName=customerName==null?"":customerName.trim();
            customerId=customerId==null?"":customerId.trim();
            mobileSuffix=mobileSuffix==null?"":mobileSuffix.trim();
            allowedFilters=allowedFilters==null?Set.of():Set.copyOf(allowedFilters);
        }
        public boolean constrained(){return !customerName.isBlank() || !customerId.isBlank() || !mobileSuffix.isBlank();}
    }

    /**
     * v1.5 检索语义：scope.constraint 是已确定且不可修改的原问句条件；
     * filter 是可空的附加筛选，只允许使用原条件之外的字段。
     */
    public SearchResult search(CurrentUser user,SearchScope searchScope,String filter,int page,int size){
        if(searchScope==null)searchScope=new SearchScope("",false,"","",Set.of(FILTER_NAME,FILTER_ID,FILTER_MOBILE_SUFFIX));
        if(!searchScope.constrained() && (filter==null || filter.isBlank()))
            throw new BusinessException(400001,"请输入客户姓名、客户编号或手机号后四位");
        Map<String,Object> params=new LinkedHashMap<>();
        String cond=scope.condition("c",user,params)+" AND c.status_code='ACTIVE'";
        if(!searchScope.customerId().isBlank()){
            cond+=" AND c.customer_id=:baseCustomerId";params.put("baseCustomerId",searchScope.customerId().toUpperCase());
        }
        if(!searchScope.mobileSuffix().isBlank()){
            cond+=" AND RIGHT(c.mobile_masked,4)=:baseMobileSuffix";params.put("baseMobileSuffix",searchScope.mobileSuffix());
        }
        if(!searchScope.customerName().isBlank()){
            String operator=searchScope.surname()?" LIKE ":"=";
            cond+=" AND (c.customer_name"+operator+":baseCustomerName OR (c.customer_name IS NULL AND c.customer_name_masked"+operator+":baseCustomerName))";
            params.put("baseCustomerName",searchScope.customerName()+(searchScope.surname()?"%":""));
        }
        String extra=filter==null?"":filter.trim();
        if(!extra.isBlank()){
            String filterType=filterType(extra);
            if(!searchScope.allowedFilters().contains(filterType))
                throw new BusinessException(400001,"该筛选条件与原查询条件重复，请使用其他可用信息筛选");
            if(FILTER_MOBILE_SUFFIX.equals(filterType)){
                cond+=" AND RIGHT(c.mobile_masked,4)=:extra";params.put("extra",extra);
            }else if(FILTER_ID.equals(filterType)){
                cond+=" AND c.customer_id LIKE :extra";params.put("extra",extra.toUpperCase()+"%");
            }else{
                // 包含匹配：输入“小明”能命中“王小明”，输入全名亦可。
                cond+=" AND (c.customer_name LIKE :extra OR (c.customer_name IS NULL AND c.customer_name_masked LIKE :extra))";
                params.put("extra","%"+extra+"%");
            }
        }
        Integer total=jdbc.queryForObject("SELECT COUNT(*) FROM dim_customer c WHERE "+cond,params,Integer.class);
        params.put("limit",size);params.put("offset",(page-1)*size);
        List<Candidate> items=jdbc.query("SELECT c.customer_id,c.customer_name,c.customer_name_masked,c.branch_id,c.mobile_masked FROM dim_customer c WHERE "+cond+" ORDER BY c.customer_id LIMIT :limit OFFSET :offset",params,
                (rs,n)->new Candidate(rs.getString(1),mask(rs.getString(2)==null?rs.getString(3):rs.getString(2)),rs.getString(4),com.boc.nl2sql.common.privacy.CustomerMasking.mobile(rs.getString(5))));
        return new SearchResult(total==null?0:total,page,size,items);
    }

    /** 已完成权限校验的单客只读卡片；仅供所属任务状态展示，所有字段仍按展示口径脱敏。 */
    public Optional<Candidate> card(String customerId){
        if(customerId==null||customerId.isBlank())return Optional.empty();
        List<Candidate> rows=jdbc.query("SELECT c.customer_id,c.customer_name,c.customer_name_masked,c.branch_id,c.mobile_masked FROM dim_customer c WHERE c.customer_id=:customerId LIMIT 1",
                Map.of("customerId",customerId),(rs,n)->new Candidate(rs.getString(1),
                        mask(rs.getString(2)==null?rs.getString(3):rs.getString(2)),rs.getString(4),
                        com.boc.nl2sql.common.privacy.CustomerMasking.mobile(rs.getString(5))));
        return rows.stream().findFirst();
    }

    private String filterType(String value){
        if(value.matches("\\d{4}"))return FILTER_MOBILE_SUFFIX;
        if(value.matches("(?i)C[0-9]{0,8}"))return FILTER_ID;
        if(value.matches("[\\p{IsHan}]+"))return FILTER_NAME;
        throw new BusinessException(400001,"筛选可识别为：汉字姓名（含名）、C开头的客户编号、或4位手机号后四位");
    }

    public Optional<ClarificationQuestion> inspect(QueryTaskEntity task,CurrentUser user){
        if(task.getResolvedCustomerId()!=null){
            if(find(user,task.getResolvedCustomerId(),null,null).isEmpty())throw new BusinessException(404001,"当前可查询范围内未找到该客户，请重新选择");
            return Optional.empty();
        }
        String text=task.getMergedQueryText();var id=ID.matcher(text);Mention mention=mention(text);
        if(task.getMultiCustomersJson()!=null)return inspectMulti(task,user);
        if(multiplePersons(text)){
            // 两人对比：构建指代队列。每一位若唯一则自动确认；多结果才打开筛选列表。
            Set<String> persons=new LinkedHashSet<>();
            var titled=TITLE.matcher(text);
            while(titled.find()){
                String value=titled.group(1).replaceFirst("^(一下|客户|查|看|找)","");
                if(value.length()>0 && SURNAMES.contains(value.substring(0,1))) persons.add(value+titled.group(2));
            }
            var names=NAME.matcher(text);
            while(names.find()){
                String n=names.group(1);
                if(isPlausibleName(n)) persons.add(n);
            }
            if(persons.size()>=2){
                var queue=new java.util.ArrayList<Map<String,Object>>();
                int index=1;
                for(String person:persons){
                    Mention personMention=personMention(person);
                    Map<String,Object> item=new LinkedHashMap<>();
                    item.put("referent",person);item.put("keyword",personMention.name());
                    item.put("surname",personMention.surname());item.put("customerId",null);
                    queue.add(item);
                    index++;
                }
                task.setMultiCustomersJson(jsonWritePersons(queue));
                return inspectMulti(task,user);
            }
        }
        if(id.find()){
            String value=id.group().toUpperCase(Locale.ROOT);var matches=find(user,value,mention,null);
            if(matches.size()==1){task.setResolvedCustomerId(value);task.setMergedQueryText(safeText(text,mention));return Optional.empty();}
            throw notFound();
        }
        String suffix=null;var tail=MOBILE_SUFFIX.matcher(text);if(tail.find())suffix=tail.group(1);
        if(mention==null && suffix!=null)return resolveSingle(task,user,text,null,suffix,"CUSTOMER_IDENTITY");
        if(mention==null){
            if(text.matches("(?s).*(他的|她的|这位客户|该客户|已确认客户).*"))
                return Optional.of(customerQuestion("CUSTOMER_IDENTITY","请输入信息筛选客户。",scopeFor(null,null,null),Map.of()));
            return Optional.empty();
        }
        return resolveSingle(task,user,text,mention,suffix,"CUSTOMER_SELECTION");
    }

    private Optional<ClarificationQuestion> resolveSingle(QueryTaskEntity task,CurrentUser user,String text,Mention mention,String suffix,String type){
        var candidates=find(user,null,mention,suffix);
        if(candidates.isEmpty())throw notFound();
        if(candidates.size()==1){
            task.setResolvedCustomerId(candidates.get(0).customerId());
            task.setMergedQueryText(safeText(text,mention));
            return Optional.empty();
        }
        return Optional.of(customerQuestion(type,selectionPrompt(mention,suffix),
                scopeFor(mention,null,suffix),Map.of()));
    }

    private String selectionPrompt(Mention mention,String suffix){
        List<String> constraints=new ArrayList<>();
        if(mention!=null)constraints.add(mention.surname()?mention.text():"姓名“"+mask(mention.name())+"”");
        if(suffix!=null&&!suffix.isBlank())constraints.add("手机号后四位 "+suffix);
        String subject=constraints.isEmpty()?"当前条件":String.join("且",constraints);
        return subject+"对应多位客户，请选择具体客户；选择前不会查询资产或交易信息。";
    }

    private Optional<ClarificationQuestion> inspectMulti(QueryTaskEntity task,CurrentUser user){
        var persons=new java.util.ArrayList<Map<String,Object>>(java.util.Arrays.asList(jsonReadPersons(task)));
        while(true){
            var next=persons.stream().filter(p->p.get("customerId")==null).findFirst();
            if(next.isEmpty()){
                task.setCustomerIdsJson(jsonWriteIds(persons.stream().map(p->String.valueOf(p.get("customerId"))).toList()));
                task.setMultiCustomersJson(null);
                return Optional.empty();
            }
            Map<String,Object> person=next.get();
            Mention mention=new Mention("",String.valueOf(person.get("keyword")),Boolean.TRUE.equals(person.get("surname")));
            var candidates=find(user,null,mention,null);
            if(candidates.isEmpty())throw notFound();
            if(candidates.size()==1){
                String candidateId=candidates.get(0).customerId();
                if(persons.stream().anyMatch(p->candidateId.equals(p.get("customerId"))))
                    throw new BusinessException(409002,"两位指代的是同一客户，无法完成对比");
                person.put("customerId",candidateId);task.setMultiCustomersJson(jsonWritePersons(persons));
                continue;
            }
            int confirmed=(int)persons.stream().filter(p->p.get("customerId")!=null).count();
            String progress=(confirmed+1)+"/"+persons.size();
            String referent=String.valueOf(person.getOrDefault("referent",""));
            if(referent.isBlank()||referent.matches("第\\d+位客户"))referent=mention.name()+(mention.surname()?"先生":"");
            task.setMultiCustomersJson(jsonWritePersons(persons));
            return Optional.of(customerQuestion("CUSTOMER_CONFIRM","正在确认"+referent+"（第"+(confirmed+1)+"位，共"+persons.size()+"位），请选择具体客户。",
                    scopeFor(mention,null,null),Map.of("进度",progress,"当前确认对象",referent)));
        }
    }

    private Mention personMention(String person){
        var titled=TITLE.matcher(person);
        if(titled.matches()){
            String name=titled.group(1);
            boolean surname=name.length()==1 || Set.of("欧阳","上官","司马","诸葛","慕容").contains(name);
            return new Mention(person,name,surname);
        }
        return new Mention(person,person,false);
    }

    private SearchScope scopeFor(Mention mention,String customerId,String suffix){
        Set<String> allowed=new LinkedHashSet<>(List.of(FILTER_NAME,FILTER_ID,FILTER_MOBILE_SUFFIX));
        // 完整姓名已经是固定条件时不能再次按姓名筛选；姓氏只是范围条件，仍允许用姓名片段继续缩小。
        if(mention!=null&&!mention.surname())allowed.remove(FILTER_NAME);
        if(customerId!=null&&!customerId.isBlank())allowed.remove(FILTER_ID);
        if(suffix!=null&&!suffix.isBlank())allowed.remove(FILTER_MOBILE_SUFFIX);
        return new SearchScope(mention==null?"":mention.name(),mention!=null&&mention.surname(),customerId,suffix,allowed);
    }

    private ClarificationQuestion customerQuestion(String type,String prompt,SearchScope searchScope,Map<String,String> extras){
        Map<String,String> slots=new LinkedHashMap<>(extras);
        if(!searchScope.customerName().isBlank()){slots.put("固定姓名",searchScope.customerName());slots.put("姓名匹配方式",searchScope.surname()?"SURNAME":"EXACT");}
        if(!searchScope.customerId().isBlank())slots.put("固定客户编号",searchScope.customerId());
        if(!searchScope.mobileSuffix().isBlank())slots.put("固定手机号后四位",searchScope.mobileSuffix());
        slots.put("筛选类型",String.join(",",searchScope.allowedFilters()));
        return new ClarificationQuestion(UUID.randomUUID().toString(),type,prompt,List.of(),slots,List.of());
    }

    public static SearchScope scopeFromSlots(Map<String,String> slots){
        if(slots==null)slots=Map.of();
        Set<String> allowed=new LinkedHashSet<>();
        String encoded=slots.getOrDefault("筛选类型","");
        if(!encoded.isBlank())for(String value:encoded.split(","))if(!value.isBlank())allowed.add(value);
        if(allowed.isEmpty())allowed.addAll(List.of(FILTER_NAME,FILTER_ID,FILTER_MOBILE_SUFFIX));
        return new SearchScope(slots.get("固定姓名"),"SURNAME".equals(slots.get("姓名匹配方式")),
                slots.get("固定客户编号"),slots.get("固定手机号后四位"),allowed);
    }

    private BusinessException notFound(){return new BusinessException(404001,"当前权限范围内未找到符合条件的客户");}

    /** v1.5 两人对比：确认当前指代位的客户（固定条件内、非重复），推进队列。 */
    public void confirmMulti(QueryTaskEntity task,CurrentUser user,String answer){
        var id=ID.matcher(answer);
        if(!id.find())throw new BusinessException(400006,"请在浮窗中通过检索选择客户，或输入 C 加8位数字的客户编号");
        String value=id.group().toUpperCase(Locale.ROOT);
        var persons=new java.util.ArrayList<Map<String,Object>>(java.util.Arrays.asList(jsonReadPersons(task)));
        var next=persons.stream().filter(p->p.get("customerId")==null).findFirst().orElseThrow();
        Mention mention=new Mention("",String.valueOf(next.get("keyword")),Boolean.TRUE.equals(next.get("surname")));
        if(find(user,value,mention,null).isEmpty())throw new BusinessException(404001,"所选客户不符合原查询条件");
        boolean duplicate=persons.stream().anyMatch(p->value.equals(p.get("customerId")));
        if(duplicate)throw new BusinessException(409002,"两位指代的是同一客户，请为当前指代重新选择另一位客户");
        next.put("customerId",value);
        task.setMultiCustomersJson(jsonWritePersons(persons));
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object>[] jsonReadPersons(QueryTaskEntity task){
        return json.readValue(task.getMultiCustomersJson(),Map[].class);
    }
    private String jsonWritePersons(java.util.List<Map<String,Object>> persons){
        return json.writeValueAsString(persons);
    }
    private String jsonWriteIds(java.util.List<String> ids){
        return json.writeValueAsString(ids);
    }

    public void answer(QueryTaskEntity task,CurrentUser user,ClarificationQuestion question,String answer){
        var id=ID.matcher(answer);
        if(id.find()){
            String value=id.group().toUpperCase(Locale.ROOT);
            SearchScope searchScope=scopeFromSlots(question.recognizedSlots());
            Mention fixedName=searchScope.customerName().isBlank()?null:
                    new Mention("",searchScope.customerName(),searchScope.surname());
            String fixedId=searchScope.customerId().isBlank()?value:searchScope.customerId();
            if(!fixedId.equalsIgnoreCase(value) || find(user,value,fixedName,
                    searchScope.mobileSuffix().isBlank()?null:searchScope.mobileSuffix()).isEmpty())
                throw new BusinessException(404001,"所选客户不符合原查询条件");
            task.setResolvedCustomerId(value);task.setMergedQueryText(safeText(task.getMergedQueryText(),mention(task.getMergedQueryText())));
        }else{
            // 新定位信息替换旧补充，防止姓名与尾号无限追加。
            String base=task.getMergedQueryText().split("，客户定位信息：",2)[0];
            task.setMergedQueryText(base+"，客户定位信息："+answer);
        }
    }
    public void answer(QueryTaskEntity task,CurrentUser user,ClarificationQuestion question,String answer,String identityType){
        // v1.5：检索浮窗统一提交客户编号；旧客户端的类型化补充保留格式校验。
        if(!"CUSTOMER_SELECTION".equals(question.type()) && identityType!=null){
            boolean valid=switch(identityType){
                case "CUSTOMER_ID" -> answer.matches("(?i)C[0-9]{8}");
                case "CUSTOMER_NAME" -> answer.matches("[\\p{IsHan}]{2,4}");
                case "MOBILE_SUFFIX" -> answer.matches("[0-9]{4}");
                default -> false;
            };
            if(!valid)throw new BusinessException(400006,"输入内容与所选身份类型不符：编号为C加8位数字，虚拟姓名为2至4个汉字，手机尾号为4位数字");
        }
        answer(task,user,question,answer);
    }
    private String safeText(String text,Mention mention){
        String base=text.split("，客户定位信息：",2)[0];Mention original=mention(base);
        return original==null?base:base.replace(original.text(),"已确认客户");
    }
}
