from pathlib import Path
p=Path('src/main/resources/static/index.html');s=p.read_text(encoding='utf-8-sig')
s=s.replace("const s=data.sheets[Number($('sheet').value)],v=data.validation;", """const s=data.sheets[Number($('sheet').value)],v=data.validation;
data.sheets.forEach((sheet,i)=>{const option=$('sheet').options?.[i];if(option)option.textContent=sheet.name+'（待修正 '+sheet.invalidRows.length+' / 通过 '+sheet.validRows.length+'）';});""")
p.write_text(s,encoding='utf-8')
p=Path('work/check-page.cjs');s=p.read_text(encoding='utf-8-sig');s=s.replace("console.log('UI grouping, original coordinates, error reasons and pagination verified');", """data.canConfirm=false;render();assert.equal($('confirmImport').disabled,true);
data.canConfirm=true;data.expiresAt='2026-10-16T03:00:00Z';render();assert.equal($('confirmImport').disabled,false);
confirming=true;render();assert.equal($('confirmImport').disabled,true);confirming=false;
receipt={batchId:'test',importedRows:2,alreadyImported:false};render();assert.equal($('confirmImport').disabled,true);assert.equal($('importState').textContent,'导入成功');
console.log('UI grouping, original coordinates, pagination and confirmation states verified');""")
p.write_text(s,encoding='utf-8')
