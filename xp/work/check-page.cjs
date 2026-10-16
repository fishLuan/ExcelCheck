const fs=require('fs'),vm=require('vm'),assert=require('assert');
class Element{constructor(){this.children=[];this.value='0';this.classList={add(){},remove(){}};this.attributes={};}append(...v){this.children.push(...v);}replaceChildren(...v){this.children=[...v];}setAttribute(k,v){this.attributes[k]=v;}addEventListener(){} }
const elements=new Map();const document={getElementById(id){if(!elements.has(id))elements.set(id,new Element());return elements.get(id);},createElement(){return new Element();}};
const code=fs.readFileSync('src/main/resources/static/index.html','utf8').match(/<script>([\s\S]*?)<\/script>/)[1];
vm.runInNewContext(code+`
const issue={sheet:'名单',rowNumber:9,columnNumber:4,field:'手机号',message:'必须以1开头'};
data={fileName:'测试.xlsx',validation:{totalRows:2,validRows:1,invalidRows:1,issues:[issue],notices:[]},sheets:[{name:'名单',columnCount:4,rows:[{rowNumber:3,cells:['供应商名称','统一社会信用代码','联系人','手机号']}],headerIssues:[],validRows:[{rowNumber:5,cells:['甲'],issues:[]}],invalidRows:[{rowNumber:9,cells:['乙','','','23800138000'],issues:[issue]}]}]};
render();assert.equal($('body').children.length,1);assert.equal($('body').children[0].children[0].textContent,'9');assert.ok($('body').children[0].children.at(-1).children[0].textContent.includes('D9'));
group='valid';render();assert.equal($('body').children[0].children[0].textContent,'5');assert.equal($('body').children[0].children.length,5);
data.sheets[0].validRows=Array.from({length:105},(_,i)=>({rowNumber:100+i*2,cells:['甲'],issues:[]}));page=1;render();assert.equal($('body').children.length,5);assert.equal($('body').children[0].children[0].textContent,'300');
data.canConfirm=false;render();assert.equal($('confirmImport').disabled,true);
data.canConfirm=true;data.expiresAt='2026-10-16T03:00:00Z';render();assert.equal($('confirmImport').disabled,false);
confirming=true;render();assert.equal($('confirmImport').disabled,true);confirming=false;
receipt={batchId:'test',importedRows:2,alreadyImported:false};render();assert.equal($('confirmImport').disabled,true);assert.equal($('importState').textContent,'导入成功');
console.log('UI grouping, original coordinates, pagination and confirmation states verified');
`,{document,assert,console,AbortController,setTimeout,clearTimeout});
