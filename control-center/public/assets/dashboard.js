(function(root){
  'use strict';
  const colors=['#72e6bf','#88bcff','#b7a5f9','#f0c47b'];
  function svg(tag,attributes={},text){const node=document.createElementNS('http://www.w3.org/2000/svg',tag);for(const[k,v]of Object.entries(attributes))node.setAttribute(k,String(v));if(text!==undefined)node.textContent=text;return node;}
  function legend(entries){const row=el('div',null,'chart-legend');for(const[label,color]of entries){const item=el('span'),dot=el('i');dot.style.background=color;item.append(dot,document.createTextNode(label));row.append(item);}return row;}
  function card(label,value,note,glyph,tone,tag,hash){const box=el('a',null,'metric-card '+tone);box.href='#'+hash;const top=el('div',null,'metric-top'),symbol=el('span',null,'metric-symbol');symbol.append(icon(glyph));top.append(el('span',label,'metric-label'),symbol);box.append(top,el('strong',String(value)),el('small',note));return box;}
  function activityChart(days){
    const box=panel('Device Activity');box.classList.add('chart-panel','activity-chart');box.append(el('span','7 days · UTC','chart-period'));
    const chart=svg('svg',{viewBox:'0 0 620 250',role:'img','aria-label':'Latest device contact and enrollments by UTC day. Exact values are in the table below.'});
    const defs=svg('defs'),gradient=svg('linearGradient',{id:'contact-area',x1:0,y1:0,x2:0,y2:1});gradient.append(svg('stop',{offset:0,'stop-color':colors[0],'stop-opacity':'.28'}),svg('stop',{offset:1,'stop-color':colors[0],'stop-opacity':0}));defs.append(gradient);chart.append(defs);
    const max=Math.max(4,...days.flatMap(d=>[d.latest_contact,d.enrolled])),ceiling=Math.ceil(max/4)*4,x=i=>48+i*90,y=n=>204-n/ceiling*168;
    for(let i=0;i<5;i++){const value=i*ceiling/4;chart.append(svg('line',{x1:40,x2:606,y1:y(value),y2:y(value),class:'chart-gridline'}),svg('text',{x:30,y:y(value)+4,'text-anchor':'end'},String(value)));}
    const points=days.map((d,i)=>[x(i),y(d.latest_contact)]),path=points.map(([px,py],i)=>(i?'L':'M')+px+' '+py).join(' ');
    if(points.length)chart.append(svg('path',{d:path+' L'+x(points.length-1)+' 204 L48 204Z',fill:'url(#contact-area)'}));
    days.forEach((d,i)=>{const bar=svg('rect',{x:x(i)-12,y:y(d.enrolled),width:24,height:204-y(d.enrolled),rx:2,fill:'#35b59a'});bar.append(svg('title',{},d.day+': '+d.enrolled+' enrolled'));chart.append(bar,svg('text',{x:x(i),y:229,'text-anchor':'middle'},new Date(d.day+'T00:00:00Z').toLocaleDateString(undefined,{weekday:'short',timeZone:'UTC'})));});
    chart.append(svg('path',{d:path,fill:'none',stroke:colors[0],'stroke-width':2}));points.forEach(([px,py],i)=>{const point=svg('circle',{cx:px,cy:py,r:3,fill:'#f28aff'});point.append(svg('title',{},days[i].day+': '+days[i].latest_contact+' latest contacts'));chart.append(point);});
    box.append(chart,legend([['Latest contact',colors[0]],['Enrolled','#35b59a']]));
    const detail=el('details',null,'chart-explanation');detail.append(el('summary','What this chart measures'),el('p','A snapshot of each visible device’s latest check-in date and enrollment date. Each device contributes at most once to each series. This is not a history of all check-ins or a measure of uptime.','hint'),table(['UTC date','Latest contact','Enrolled'],days.map(d=>[d.day,d.latest_contact,d.enrolled])));box.append(detail);return box;
  }
  function accessChart(access){
    const box=panel('Device Access');box.classList.add('chart-panel','access-chart');const items=[['Active',access.active],['Awaiting enrollment',access.pending],['Suspended',access.suspended],['Blocked',access.blocked]],total=items.reduce((n,[,value])=>n+value,0);
    const chart=svg('svg',{viewBox:'0 0 240 220',role:'img','aria-label':items.map(([label,value])=>label+': '+value).join(', ')}),group=svg('g',{transform:'rotate(-90 120 110)',fill:'none','stroke-width':35});group.append(svg('circle',{cx:120,cy:110,r:70,class:'donut-track'}));let offset=0;const circumference=2*Math.PI*70;
    if(total)items.forEach(([label,value],i)=>{if(!value)return;const length=value/total*circumference,gap=items.filter(([,n])=>n>0).length>1?Math.min(3,length*.15):0,arc=svg('circle',{cx:120,cy:110,r:70,stroke:colors[i],'stroke-dasharray':Math.max(0,length-gap)+' '+circumference,'stroke-dashoffset':-offset});arc.append(svg('title',{},label+': '+value));group.append(arc);offset+=length;});chart.append(group);
    if(!total)chart.append(svg('text',{x:120,y:114,'text-anchor':'middle'},'No devices yet'));
    box.append(chart,legend(items.map(([label,value],i)=>[label+' · '+value,colors[i]])));return box;
  }
  async function render(epoch){
    const main=$('main');main.classList.add('dashboard-page');const head=el('div',null,'page-heading dashboard-toolbar'),intro=el('div');intro.append(el('span','WORKSPACE OVERVIEW','welcome-label'),el('h1','Everything, connected.'),el('p','Your customers, devices and requests in one workspace.'));head.append(intro,button('Find a customer',()=>{location.hash='customers';},'primary'));main.replaceChildren(head);scopePicker(main);
    const loading=el('p','Loading workspace activity…','hint');main.append(loading);
    const stats=await api('/api/overview?'+scopeQuery().slice(1));if(epoch!==state.epoch)return;loading.remove();
    const cards=el('div',null,'dashboard-metrics');cards.append(card('Online devices',stats.connected,'Of '+stats.total+' devices · Latest check-ins','devices','mint','Online','devices'),card('Customers',stats.customers,'Accounts in this workspace','customers','cyan','Customers','customers'),card('Needs attention',stats.recovery_holds+stats.payment_review,'Recovery requests and payment reviews','refresh','rose','Review','recovery-holds'),card('Total devices',stats.total,'Visible customer devices','devices','violet','Fleet','devices'));main.append(cards);
    const strip=el('div',null,'metric-strip');for(const[label,value,hash]of [['Pending app actions',stats.pending_commands,'devices'],['Awaiting enrollment',stats.not_enrolled,'devices'],['Restricted devices',stats.restricted,'devices'],['Payments to review',stats.payment_review,'renewals/payments']]){const link=el('a');link.href='#'+hash;link.append(el('strong',String(value)),el('span',label));strip.append(link);}main.append(strip);
    const charts=el('div',null,'analytics-grid');charts.append(activityChart(stats.activity));main.append(charts);
    const queue=panel('Needs Your Attention');queue.classList.add('dashboard-queue');
    function row(title,note,label,hash){const item=el('div',null,'summary-row'),text=el('div');text.append(el('h3',title),el('p',note));item.append(text,button(label,()=>{location.hash=hash;},'review-button'));queue.append(item);}
    for(const request of stats.recovery_requests)row(request.label||'Device reconnection',[request.model,'Recovery requested'].filter(Boolean).join(' · '),'Review','recovery-holds');
    if(stats.recovery_holds>stats.recovery_requests.length)row('More recovery requests',stats.recovery_holds+' pending across this scope','View all','recovery-holds');
    if(stats.payment_review)row('Payment & renewal review',stats.payment_review+' order(s) require review. Open the appropriate payment workspace.','Open payments','renewals/payments');
    if(!stats.recovery_holds&&!stats.payment_review)row('No recovery or payment reviews pending','Find a customer to manage devices and support.','Find customer','customers');
    charts.append(queue);
    const lower=el('div',null,'analytics-grid');const tools=panel('Workspace tools');
    for(const [title,note,hash] of [['App branding','Your identity, artwork and app releases','branding'],['Setup library','Saved layouts and device preferences','library'],['Customers','Find a customer and manage their devices','customers']]){const link=el('a',null,'dashboard-tool');link.href='#'+hash;link.append(el('strong',title),el('span',note));tools.append(link);}
    const fleet=panel('Your devices');fleet.append(el('p','Open a device to manage it.','hint'));lower.append(fleet,tools);main.append(lower,el('p','Online reflects latest authenticated app contact. App actions require a confirmed response.','dashboard-footnote'));
    try {const data=await api('/api/devices?page=1'+scopeQuery());if(epoch!==state.epoch)return;fleet.append(table(['Device / customer','Status',''],data.items.slice(0,4).map(d=>{const name=el('div');name.append(el('strong',d.label||d.model||'Device'),el('div',d.customer_name||d.line_username||'','cell-sub'));return [name,badge(d.presence),button('Manage →',()=>{location.hash='device/'+d.id;},'quiet')];})));}catch(e){if(epoch===state.epoch)fleet.append(el('p',e.message,'error'));}
  }
  root.ControlDashboard={render};
})(window);
