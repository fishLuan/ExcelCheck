from pathlib import Path
p=Path('src/main/resources/static/index.html');s=p.read_text(encoding='utf-8-sig')
s=s.replace('<label class="filter"><input type="checkbox" id="onlyErrors">只看有问题的行（当前工作表）</label>','''<div class="toolbar"><button id="export">导出全部错误（Word）</button><button id="correct">修正后重新上传</button></div><p>下载错误报告，在原 Excel 中修正并保存后重新上传，系统将重新校验全部数据。</p><div class="toolbar" role="group" aria-label="校验结果分组"><button id="invalidTab" aria-pressed="true">待修正</button><button id="validTab" aria-pressed="false">校验通过</button></div>''')
s=s.replace('</style>','button[aria-pressed="false"]{background:#e8edf1;color:#344b60}#export{background:#344b60}.problems{max-width:none;overflow:visible;text-overflow:clip}td.bad{outline:1px solid #edc2b9}</style>')
s=s.replace("let selected=null,data=null,page=0;", "let selected=null,data=null,page=0,group='invalid',exporting=false;")
s=s.replace("data=result;page=0;", "data=result;page=0;group=result.validation.issues.length?'invalid':'valid';")
s=s.replace("'（'+s.rows.length+' 行）'", "'（待修正 '+s.invalidRows.length+' / 通过 '+s.validRows.length+'）'")
s=s.replace("'解析完成，可以核对下方数据。'", "'校验完成，请分别查看待修正和校验通过的数据。'")
start=s.index('function render(){');s=s[:start]+'''function render(){
const s=data.sheets[Number($('sheet').value)],v=data.validation;
$('summary').textContent='共 '+v.totalRows+' 条数据，'+v.validRows+' 条通过字段与文件内查重，'+v.invalidRows+' 条待修正。';
$('notices').replaceChildren(...v.notices.map(t=>cell('p',t)));
$('export').disabled=exporting||!v.issues.length;
$('invalidTab').textContent='待修正（'+s.invalidRows.length+'）';$('validTab').textContent='校验通过（'+s.validRows.length+'）';
$('invalidTab').setAttribute('aria-pressed',String(group==='invalid'));$('validTab').setAttribute('aria-pressed',String(group==='valid'));
$('schema').hidden=!s.headerIssues.length;$('schema').textContent=s.headerIssues.map(e=>'Excel 第 '+e.rowNumber+' 行 · '+e.field+'：'+e.message).join('\\n');
const rows=group==='invalid'?s.invalidRows:s.validRows;
$('meta').textContent=data.fileName+' · '+s.name+' · '+(group==='invalid'?'待修正':'校验通过')+' '+rows.length+' 行';
const tr=document.createElement('tr');tr.append(cell('th','Excel 原始行号'));
for(let i=0;i<s.columnCount;i++)tr.append(cell('th',column(i)+' · '+(s.rows[0]?.cells[i]||'')));
if(group==='invalid')tr.append(cell('th','错误字段 / 原因'));$('head').replaceChildren(tr);$('body').replaceChildren();
for(const row of rows.slice(page*size,(page+1)*size)){
 const tr=document.createElement('tr');tr.append(cell('th',String(row.rowNumber)));
 for(let i=0;i<s.columnCount;i++){const td=cell('td',row.cells[i]||'');if(row.issues.some(e=>e.columnNumber===i+1))td.classList.add('bad');tr.append(td);}
 if(group==='invalid'){const td=document.createElement('td');td.className='problems bad';for(const e of row.issues){const line=document.createElement('div');line.textContent=(e.columnNumber?column(e.columnNumber-1)+e.rowNumber+' · ':'')+e.field+'：'+e.message;td.append(line);}tr.append(td);}
 $('body').append(tr);
}
const pages=Math.max(1,Math.ceil(rows.length/size));$('count').textContent=rows.length?'显示第 '+(page*size+1)+'–'+Math.min((page+1)*size,rows.length)+' 条（左侧为 Excel 原始行号）':group==='invalid'?'当前工作表没有待修正的数据行；如有表头错误，请先修正表头。':'当前工作表没有通过校验的数据行。';
$('page').textContent=(page+1)+' / '+pages;$('prev').disabled=page===0;$('next').disabled=page>=pages-1;
}
for(const kind of ['invalid','valid'])$(kind+'Tab').addEventListener('click',()=>{group=kind;page=0;render();});
$('sheet').addEventListener('change',()=>{page=0;render();});$('prev').addEventListener('click',()=>{page--;render();});$('next').addEventListener('click',()=>{page++;render();});
$('correct').addEventListener('click',()=>{$('file').value='';$('file').click();});
$('export').addEventListener('click',async()=>{
if(!selected||!data||exporting)return;
const file=selected;exporting=true;$('export').disabled=true;$('error').hidden=true;$('export').textContent='正在生成 Word…';
const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),120000);
try{const form=new FormData();form.append('file',file);const response=await fetch('/api/errors/word',{method:'POST',body:form,signal:controller.signal});
if(!response.ok){let message='报告导出失败，请重试。';try{message=(await response.json()).message||message;}catch{}throw new Error(message);}
const blob=await response.blob();const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download=file.name.replace(/\\.xlsx$/i,'')+'-校验错误报告.docx';document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),30000);
}catch(e){fail(e.name==='AbortError'?'报告生成超时，请稍后重试。':e instanceof TypeError?'无法连接服务器，错误报告未能导出，请重试。':e.message);}
finally{clearTimeout(timer);exporting=false;$('export').textContent='导出全部错误（Word）';if(data)render();}
});
</script></body></html>
'''
p.write_text(s,encoding='utf-8')
