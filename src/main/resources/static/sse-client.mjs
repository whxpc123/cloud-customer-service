/** 第十八章 POST SSE 分帧器：网络块可以拆开 UTF-8 和事件；不自动重新发送 POST。 */
export async function consumeSse(body, onEvent, maxFrame = 65536) {
  if (!body) throw new Error('响应没有可读数据流');
  const reader = body.getReader(), decoder = new TextDecoder('utf-8', {fatal:true});
  let line='', skipLf=false, eventName='message', data=[], id='', frameSize=0;
  function acceptLine(value) {
    if (value==='') {
      if (data.length) onEvent(eventName, data.join('\n'), {id});
      eventName='message';data=[];id='';frameSize=0;return;
    }
    frameSize+=value.length+1;
    if (frameSize>maxFrame) throw new Error('事件过大');
    if (value.startsWith(':')) return;
    const colon=value.indexOf(':');
    const field=colon<0?value:value.slice(0,colon);
    let content=colon<0?'':value.slice(colon+1);
    if (content.startsWith(' ')) content=content.slice(1);
    if (field==='event') eventName=content||'message';
    if (field==='data') data.push(content);
    if (field==='id'&&!content.includes('\0')) id=content;
  }
  function decodeText(text) {
    // 逐字符识别 CR、LF、CRLF。CRLF 跨网络块时只算一个换行。
    for (const char of text) {
      if (skipLf) {skipLf=false;if(char==='\n')continue;}
      if (char==='\r'||char==='\n') {acceptLine(line);line='';skipLf=char==='\r';}
      else {line+=char;if(line.length>maxFrame)throw new Error('事件行过大');}
    }
  }
  try {
    while(true){const {value,done}=await reader.read();if(done)break;decodeText(decoder.decode(value,{stream:true}));}
    decodeText(decoder.decode());
    // 没有空行终止的尾部不派发。调用者还必须确认 completed / failed，EOF 自身不表示成功。
  } finally {
    await reader.cancel().catch(()=>{});reader.releaseLock();
  }
}

/** 一轮模型事件的协议检查，保持状态版本与文本序号彼此独立。纯函数式回调方便离线验证。 */
export function createAnswerTracker(onDelta=()=>{},onStatus=()=>{}) {
  let turnId=null,lastSequence=0,started=false,terminal=false,status='connecting',deltas=0,characters=0;
  function accept(name,raw) {
    const packet=JSON.parse(raw);
    if(!packet||!Number.isSafeInteger(packet.sequence)||packet.sequence<1||typeof packet.text!=='string'
        ||typeof packet.turnId!=='string'||!/^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(packet.turnId))
      throw new Error('非法模型事件');
    if(turnId!==null&&packet.turnId!==turnId)throw new Error('不同回答的片段发生混用');
    if(packet.sequence<=lastSequence)return;
    if(terminal||packet.sequence!==lastSequence+1)throw new Error('事件顺序不完整');
    if(!['turn.started','answer.delta','turn.completed','turn.failed'].includes(name))throw new Error('未知模型事件');
    if(!started&&name!=='turn.started'||started&&name==='turn.started')throw new Error('开始事件顺序错误');
    turnId=packet.turnId;lastSequence=packet.sequence;
    if(name==='turn.started'){started=true;status='streaming';}
    if(name==='answer.delta'){deltas++;characters+=packet.text.length;onDelta(packet.text);}
    if(name==='turn.completed'){terminal=true;status='completed';}
    if(name==='turn.failed'){terminal=true;status='failed';}
    onStatus(name,packet);
  }
  return {accept,snapshot:()=>({turnId,lastSequence,started,terminal,status,deltas,characters})};
}
