/** 第 25 章：浏览器不推导业务路线，只画服务器定义，并高亮真实 Graph 返回的节点轨迹。 */
const $ = id => document.getElementById(id);
const base = '/internal/draft-tasks/flow', ns = 'http://www.w3.org/2000/svg';
let catalog = null, latest = null, selected = '', selectedNode = '', csrf = '', csrfHeader = '', allowed = false, busy = false;
const statuses = {
  WAITING_APPROVAL: ['等待操作审批', '本次检查已结束，模拟方法没有执行。这不是可恢复的框架中断。'],
  REJECTED: ['已拒绝', '拒绝分支已经结束，未进入模拟执行节点。'],
  BLOCKED: ['已阻断', '业务条件不满足，本次流程停止。通过调用计数可以区分在哪一步被阻断。'],
  SIMULATION_COMPLETED: ['模拟完成', '审批与执行前复核通过，模拟一次。没有创建真实申请或执行退款。'],
  RECONCILIATION_REQUIRED: ['结果待核查', '模拟方法返回 UNKNOWN，流程进入核查，没有自动重试。测试探针记录的副作用次数不等于真实系统已确认成功。']
};

/** 所有请求同源、禁止缓存；出错不重放 POST，不把 HTTP 200 等同于提交成功。 */
async function api(path, method = 'GET', body) {
  const response = await fetch(path, {method, credentials:'same-origin', cache:'no-store',
    headers:method === 'GET' ? {} : {'Content-Type':'application/json', [csrfHeader]:csrf},
    ...(body === undefined ? {} : {body:JSON.stringify(body)})});
  const data = await response.json().catch(() => ({}));
  if (!response.ok) { const error = new Error(data.message || `请求未完成（HTTP ${response.status}）`); error.status=response.status; error.code=data.code; throw error; }
  return data;
}
function controls() {
  $('scenario-list').disabled = busy || !allowed;
  $('run').disabled = busy || !allowed || !selected;
  $('refresh').disabled = busy;
  $('download').disabled = !catalog;
  $('result-panel').setAttribute('aria-busy', String(busy));
}
function textNode(tag, text, className) {
  const node=document.createElement(tag); node.textContent=text; if(className)node.className=className; return node;
}
function svgNode(tag, attributes = {}, text) {
  const node=document.createElementNS(ns,tag);
  for(const [key,value] of Object.entries(attributes))node.setAttribute(key,String(value));
  if(text !== undefined)node.textContent=text;
  return node;
}

/** 图结构来自后端；SVG 只处理坐标和选中态，不为审批条件补默认值。 */
function draw() {
  const svg=$('diagram'); svg.replaceChildren(); if(!catalog)return;
  const {nodes,edges}=catalog.definition, byId=new Map(nodes.map(n=>[n.id,n]));
  const path=latest ? ['__START__',...latest.result.trace,'__END__'] : [];
  const traversed=new Set(path.slice(1).map((id,i)=>`${path[i]}>${id}`));
  const defs=svgNode('defs');
  for(const [id,color] of [['arrow','#b8cad1'],['arrow-active','#396970']]) {
    const marker=svgNode('marker',{id,viewBox:'0 0 10 10',refX:9,refY:5,markerWidth:6,markerHeight:6,orient:'auto-start-reverse'});
    marker.append(svgNode('path',{d:'M 0 0 L 10 5 L 0 10 z',fill:color}));defs.append(marker);
  }
  svg.append(defs);
  for(const edge of edges) {
    const from=byId.get(edge.from),to=byId.get(edge.to),x1=from.x+90,y1=from.y+70,x2=to.x+90,y2=to.y;
    const middle=(y1+y2)/2,active=traversed.has(`${edge.from}>${edge.to}`);
    svg.append(svgNode('path',{d:`M ${x1} ${y1} C ${x1} ${middle} ${x2} ${middle} ${x2} ${y2}`,
      class:`graph-edge ${edge.label?'conditional ':''}${active?'visited':''}`,'marker-end':`url(#${active?'arrow-active':'arrow'})`}));
    if(edge.label)svg.append(svgNode('text',{x:(x1+x2)/2+8,y:middle-7,class:`edge-label ${active?'visited':''}`},edge.label));
  }
  for(const node of nodes) {
    const boundary=node.id.startsWith('__'),visited=path.includes(node.id);
    const g=svgNode('g',{class:`graph-node ${boundary?'boundary ':''}${visited?'visited ':''}${node.id===selectedNode?'selected ':''}${node.id==='reconcile'?'unknown':''}`,
      tabindex:0,role:'button','data-node':node.id,'aria-label':`${node.title}：${node.id}`,'aria-pressed':String(node.id===selectedNode)});
    g.append(svgNode('rect',{x:node.x,y:node.y,width:180,height:70,rx:8}));
    g.append(svgNode('text',{x:node.x+15,y:node.y+28},node.title));
    g.append(svgNode('text',{x:node.x+15,y:node.y+50,class:'node-code'},node.id));
    g.addEventListener('click',()=>showNode(node.id));
    g.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();showNode(node.id);}});
    svg.append(g);
  }
}
function showNode(id) {
  const restoreFocus=document.activeElement?.getAttribute('data-node')===id;
  selectedNode=id; const node=catalog.definition.nodes.find(n=>n.id===id); if(!node)return;
  $('node-detail').replaceChildren(textNode('h3',`${node.title} · ${node.id}`),textNode('p',node.description));draw();
  if(restoreFocus)[...$('diagram').querySelectorAll('[data-node]')].find(n=>n.getAttribute('data-node')===id)?.focus();
}

/** 选择新场景或请求失败后清除旧结果，防止把上一轮成功误当成本次执行结果。 */
function clearResult() {
  latest=null;selectedNode='';$('result-heading').textContent='尚未运行';$('status').textContent='等待运行';$('status').classList.remove('warning');
  for(const id of ['approval-reads','simulation-calls','effects','model-calls'])$(id).textContent='—';
  for(const id of ['draft-check','approval-check','simulation-check'])$(id).textContent='尚未运行';
  $('trace').replaceChildren(textNode('li','尚无轨迹'));$('raw').textContent='尚无结果';$('ending').textContent='图是否正常结束、业务是否完成，将分别展示。';
  $('run-meta').textContent='每次点击产生独立运行；刷新会清空页面结果，不提供中断恢复或永久审计。';
  $('node-detail').replaceChildren(textNode('h3','从入口重新核验'),textNode('p','请选择场景并运行；只有服务器返回的真实轨迹会被高亮。'));draw();
}
function render(run) {
  latest=run;const result=run.result,info=catalog.scenarios.find(s=>s.id===run.scenario),display=statuses[result.status];
  $('result-heading').textContent=info.title;$('status').textContent=display?.[0]||result.status;
  $('status').classList.toggle('warning',result.status==='RECONCILIATION_REQUIRED'||result.status==='BLOCKED');
  $('notice').textContent=display?.[1]||'请检查服务器返回的业务状态。';
  $('approval-reads').textContent=run.counters.approvalReads;$('simulation-calls').textContent=run.counters.simulationCalls;
  $('effects').textContent=run.counters.simulatedEffects;$('model-calls').textContent=run.counters.modelCalls;
  $('ending').textContent=`本次图正常结束：${result.frameworkEnded?'是':'否'}　·　真实提交：${result.actualSubmitted?'是':'否'}　·　退款执行：${result.refundExecuted?'是':'否'}`;
  $('trace').replaceChildren();
  result.trace.forEach((id,i)=>{const li=document.createElement('li'),button=document.createElement('button');button.type='button';
    button.append(textNode('span',String(i+1).padStart(2,'0')),textNode('span',id));button.addEventListener('click',()=>showNode(id));li.append(button);$('trace').append(li);});
  for(const [id,key] of [['draft-check','draft_check'],['approval-check','approval'],['simulation-check','simulation_result']])$(id).textContent=result.checks[key]||'未执行该检查';
  $('run-meta').textContent=`本次运行 ${run.runId} · 任务/审批标识均为夹具生成的虚构标识，未访问真实草稿或审批。`;
  $('raw').textContent=JSON.stringify(run,null,2);showNode(result.trace.at(-1));
}
async function operate(work) {
  if(busy)return;busy=true;controls();
  try{await work();}catch(error){
    clearResult();$('result-heading').textContent=error.code==='GRAPH_FAILED'?'图执行失败':'请求未完成';$('status').textContent='没有正常结果';$('status').classList.add('warning');
    $('notice').textContent=error.message+' 未自动重试。';$('ending').textContent='没有获得完整终态和轨迹，不能显示为完成。';
    if(error.status===401||error.status===403){allowed=false;catalog=null;selected='';$('scenario-list').replaceChildren();$('mermaid').textContent='请重新登录';$('account').textContent='请在统一客服页登录后刷新。';draw();}
  }finally{busy=false;controls();}
}
async function refresh() {
  clearResult();catalog=null;selected='';$('scenario-list').replaceChildren();$('mermaid').textContent='登录后加载';draw();
  const session=await api('/internal/handoff/session');csrf=session.csrfToken;csrfHeader=session.csrfHeader;
  allowed=session.authenticated&&session.authorities?.includes('customer:chat');
  $('account').textContent=allowed?`当前账户：${session.username} · 仅运行独立教学夹具`:'请到统一客服页使用客户账号登录，再返回刷新。';
  if(!allowed){$('notice').textContent='登录后可以加载图定义和实验场景。';return;}
  catalog=await api(base+'/definition');$('mermaid').textContent=catalog.definition.mermaid;
  for(const scenario of catalog.scenarios){const label=document.createElement('label');label.className='scenario-option';const input=document.createElement('input');input.type='radio';input.name='scenario';input.value=scenario.id;
    if(!selected){input.checked=true;selected=scenario.id;$('scenario-description').textContent=scenario.description;}
    input.addEventListener('change',()=>{selected=scenario.id;clearResult();$('scenario-description').textContent=scenario.description;$('notice').textContent='已切换实验条件，点击运行查看实际路线。';controls();});
    label.append(input,textNode('span',scenario.title));$('scenario-list').append(label);}
  $('notice').textContent='选择一个场景，点击运行查看真实节点路径。';draw();
}
$('refresh').addEventListener('click',()=>operate(refresh));
$('run').addEventListener('click',()=>operate(async()=>{const scenario=selected;clearResult();$('notice').textContent='正在执行图，请等待实际结果…';render(await api(base+'/runs','POST',{scenario}));}));
// 本地下载来自后端实际编译图的文本，不加载远端渲染脚本或发送图数据到第三方。
$('download').addEventListener('click',()=>{if(!catalog)return;const url=URL.createObjectURL(new Blob([catalog.definition.mermaid],{type:'text/plain;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download='after-sale-submission-flow.mmd';a.click();URL.revokeObjectURL(url);});
operate(refresh);
