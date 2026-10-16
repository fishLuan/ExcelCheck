from pathlib import Path
p=Path('src/main/resources/static/index.html');s=p.read_text(encoding='utf-8-sig')
s=s.replace('③ 确认入库（后续阶段）','③ 确认入库').replace('本阶段仅预览，不会入库。','上传不会写入供应商数据；全部校验通过后，需点击确认才能整批入库。')
s=s.replace('<div class="toolbar"><button id="export">','<div class="validation"><b id="importState">等待确认</b><p id="importHint"></p><button id="confirmImport" disabled>确认入库</button></div><div class="toolbar"><button id="export">')
s=s.replace('</main>','''<dialog id="confirmDialog"><h2>确认导入供应商</h2><p id="confirmDetail"></p><p>本次将导入文件中所有工作表的数据，请确认已核对预览结果。</p><div class="toolbar"><button id="cancelImport">返回核对</button><button id="submitImport">确认写入数据库</button></div></dialog></main>''')
s=s.replace('</style>','dialog{border:1px solid #dbe3e9;border-radius:12px;max-width:550px;padding:28px}dialog::backdrop{background:#172a3b77}</style>')
s=s.replace("exporting=false;", "exporting=false,confirming=false,receipt=null;",1)
s=s.replace("function choose(file){selected=null;data=null;", "function choose(file){if(confirming)return;selected=null;data=null;receipt=null;")
s=s.replace("data=result;page=0;", "data=result;receipt=null;page=0;")
s=s.replace("' 条通过字段与文件内查重，'", "' 条通过校验与查重，'")
s=s.replace("$('export').disabled=exporting||!v.issues.length;", """$('export').disabled=confirming||exporting||!v.issues.length;
$('confirmImport').disabled=confirming||!data.canConfirm||!!receipt;
$('confirmImport').textContent=confirming?'正在入库…':receipt?'已完成入库':'确认入库（'+v.totalRows+' 条）';
$('importState').textContent=receipt?'导入成功':data.canConfirm?'待运营确认':'暂不能入库';
$('importHint').textContent=receipt?'批次 '+receipt.batchId+'，已写入 '+receipt.importedRows+' 条供应商数据。'+(receipt.alreadyImported?'本次为重复确认，未重复写入。':''):data.canConfirm?'已通过字段校验、文件内查重和数据库查重。预览有效期至 '+new Date(data.expiresAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'})+'（北京时间），确认前不会入库。':'请修正所有问题后重新上传并校验；当前文件不会部分导入。';""")
insert="""
$('confirmImport').addEventListener('click',()=>{
 if(!data?.canConfirm||receipt||confirming)return;
 $('confirmDetail').textContent='文件：'+data.fileName+'，共 '+data.validation.totalRows+' 条数据。';$('confirmDialog').showModal();
});
$('cancelImport').addEventListener('click',()=>$('confirmDialog').close());
$('submitImport').addEventListener('click',async()=>{
 if(!data?.canConfirm||receipt||confirming)return;
 $('confirmDialog').close();confirming=true;$('error').hidden=true;
 $('file').disabled=true;$('upload').disabled=true;$('correct').disabled=true;render();
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),120000);
 try{
  const response=await fetch('/api/imports/confirm',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:data.token}),signal:controller.signal});
  let result;try{result=await response.json();}catch{throw new Error('确认结果暂时无法读取，请使用当前批次重试确认，系统会防止重复入库。');}
  if(!response.ok){if(response.status===409){if(result.preview){data={...result.preview,token:data.token,canConfirm:false};group='invalid';page=0;}else data.canConfirm=false;}throw new Error(result.message||'确认失败，请重试。');}
  receipt=result;$('status').textContent='导入成功，共 '+result.importedRows+' 条。';
 }catch(e){fail(e.name==='AbortError'?'确认等待超时，入库结果暂时不明。请重试当前批次确认，系统会防止重复入库。':e instanceof TypeError?'网络中断，入库结果暂时不明。请重试当前批次确认，系统会防止重复入库。':e.message);}
 finally{clearTimeout(timer);confirming=false;$('file').disabled=false;$('upload').disabled=false;$('correct').disabled=false;render();}
});
"""
s=s.replace('</script>',insert+'\n</script>')
p.write_text(s,encoding='utf-8')
