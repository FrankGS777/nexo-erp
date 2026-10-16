import {readdir,readFile,lstat} from 'node:fs/promises';
import {resolve,relative,extname} from 'node:path';

const root=resolve(import.meta.dirname,'..');
const ignored=new Set(['.git','target','node_modules']);
const textExtensions=new Set(['.java','.sql','.yml','.yaml','.html','.css','.js','.mjs','.json','.md','.xml','.svg','.properties','.txt']);
const forbiddenExtensions=new Set(['.pem','.p12','.pfx','.key','.dump','.db','.sqlite','.sqlite3','.jks','.zip','.jar','.class']);
const forbiddenDirectories=new Set(['secrets','runtime','output','.aws','.idea','.vscode']);
const checks=[
  ['private key',/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/],
  ['cloud credential',/(?:AKIA|ASIA)[A-Z0-9]{16}/],
  ['GitHub token',/(?:gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{25,})/],
  ['deployment link',/https?:\/\/(?:erp\.|[a-z0-9-]+\.console\.aws\.)/i],
  ['personal home path',/\/(?:Users|home)\/(?!demo(?:\/|\b))[A-Za-z0-9_-]+\//],
  ['non-demo RUC',/(?<![\w])(?<!\d)(?!000)\d{11}(?!\d)/]
];
const problems=[];let files=0;
async function walk(directory){
  for(const entry of await readdir(directory,{withFileTypes:true})){
    if(ignored.has(entry.name))continue;
    const path=resolve(directory,entry.name),name=relative(root,path);
    if(entry.isSymbolicLink()){problems.push(`${name}: symbolic link`);continue;}
    if(entry.isDirectory()){if(forbiddenDirectories.has(entry.name))problems.push(`${name}: excluded directory`);else await walk(path);continue;}
    files++;
    if(forbiddenExtensions.has(extname(entry.name))||/^\.env(?:\.|$)/.test(entry.name))problems.push(`${name}: excluded file type`);
    if((await lstat(path)).size>5_000_000)problems.push(`${name}: unexpectedly large asset`);
    if(!textExtensions.has(extname(path))&& !['mvnw','mvnw.cmd','LICENSE'].includes(entry.name))continue;
    const source=await readFile(path,'utf8');
    for(const [kind,pattern] of checks)if(pattern.test(source))problems.push(`${name}: possible ${kind}`);
  }
}
await walk(root);
if(problems.length){console.error(problems.join('\n'));process.exitCode=1;}
else console.log(`Public-file audit passed: ${files} files. Review screenshots and history separately before publishing.`);
