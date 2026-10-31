"""Pure offered-package validation. No network, DB, filesystem or worker imports."""
import decimal
import hashlib
import json
import re


def package_document(plan):
    try:
        credit=decimal.Decimal(str(plan.get('credits') or '0'));duration=int(plan.get('duration') or 0);connections=int(plan.get('max_connections') or 1)
        groups=json.loads(plan.get('bouquets') or '[]');name=plan['package_name'];period=str(plan.get('duration_period') or 'days').lower();legacy=int(plan.get('duration_value') or 0)
        if not credit.is_finite() or credit<0 or credit>1000000000 or credit.normalize().as_tuple().exponent<-8 or not 1<=duration<=1000000 or not 1<=connections<=100 or not 0<=legacy<=1000000:raise ValueError()
        if not isinstance(name,str) or not 1<=len(name)<=80 or any(ord(c)<32 or ord(c)==127 for c in name):raise ValueError()
        # Paid renewal extends the existing line, preserving its bouquets. An
        # empty package bouquet list is valid; it is not a replacement list.
        if period not in {'hour','hours','day','days','week','weeks','month','months','year','years'} or not isinstance(groups,list) or len(groups)>10000:raise ValueError()
        bouquets=[]
        for v in groups:
            if type(v) is not int and not (isinstance(v,str) and re.fullmatch('[0-9]{1,10}',v)):raise ValueError()
            n=int(v)
            if not 1<=n<=2147483647:raise ValueError()
            bouquets.append(n)
        doc={'id':int(plan['id']),'name':name,'duration':duration,'duration_value':legacy,'period':period,'max_connections':connections,'credits':format(credit.normalize(),'f'),'bouquets':bouquets}
        if not 1<=doc['id']<=2147483647:raise ValueError()
    except (ValueError,TypeError,KeyError,decimal.InvalidOperation):raise ValueError('Invalid offered paid package') from None
    doc['fingerprint']=hashlib.sha256(json.dumps(doc,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
    return doc
