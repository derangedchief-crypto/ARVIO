<?php
declare(strict_types=1);
namespace ControlCenter;

/** Closed paged metadata schema; never source configuration, channel URLs or files. */
final class CommandCategories
{
    public static function payload(string $kind,array $input): array
    {
        if($kind==='get_categories') {
            Input::keys($input,['profile_ref','source_ref','page']);
            foreach(['profile_ref','source_ref'] as $field)if(array_key_exists($field,$input))EnrollmentProof::hex($input[$field],64);
            if(isset($input['profile_ref'])!==isset($input['source_ref']))throw new Problem(422,'Select a source together with its profile.');
            if(array_key_exists('page',$input))Input::integer($input['page'],1,40);
        } else {
            Input::keys($input,['profile_ref','source_ref','expected_revision','category_ref','operation']);
            foreach(['profile_ref','source_ref','expected_revision'] as $field)EnrollmentProof::hex($input[$field]??null,64);
            if(!in_array($input['operation']??null,['hide','show','move_up','move_down','move_top','reset_order'],true))throw new Problem(422,'Unsupported category operation.');
            if($input['operation']==='reset_order') { if(array_key_exists('category_ref',$input))throw new Problem(422,'Reset targets a source, not a category.'); }
            else EnrollmentProof::hex($input['category_ref']??null,64);
        }
        return $input;
    }
    public static function catalog(string $kind,mixed $input): array
    {
        if($kind!=='get_categories'||!is_array($input)||array_is_list($input))throw new Problem(422,'Invalid category snapshot.');
        $keys=['schema_version','profile_ref','source_ref','revision','page','pages','total','order_editable','sources','items'];
        Input::keys($input,$keys);if(array_diff($keys,array_keys($input)))throw new Problem(422,'Missing category snapshot fields.');
        if($input['schema_version']!==1||!is_bool($input['order_editable']))throw new Problem(422,'Invalid category snapshot version.');
        foreach(['profile_ref','source_ref','revision'] as $field)EnrollmentProof::hex($input[$field],64);
        $total=Input::integer($input['total'],1,2000);$page=Input::integer($input['page'],1,40);$pages=(int)ceil($total/50);
        if($input['pages']!==$pages||$page>$pages)throw new Problem(422,'Invalid category pagination.');
        if(!is_array($input['sources'])||!array_is_list($input['sources'])||count($input['sources'])<1||count($input['sources'])>32)throw new Problem(422,'Invalid category sources.');
        $refs=[];$selected=false;
        foreach($input['sources'] as $source) {
            if(!is_array($source))throw new Problem(422,'Invalid category source.');Input::keys($source,['ref','label','total']);
            $ref=EnrollmentProof::hex($source['ref']??null,64);$label=Input::text($source['label']??null,'source label',20);$count=Input::integer($source['total']??null,0,2000);
            if(!preg_match('/^(Playlist|Portal) [1-9][0-9]?$/D',$label)||isset($refs[$ref]))throw new Problem(422,'Invalid/duplicate category source.');
            $refs[$ref]=true;if($ref===$input['source_ref']){$selected=true;if($count!==$total)throw new Problem(422,'Category source count mismatch.');}
        }
        if(!$selected||!is_array($input['items'])||!array_is_list($input['items'])||count($input['items'])!==min(50,$total-($page-1)*50))throw new Problem(422,'Invalid category page items.');
        $refs=[];
        foreach($input['items'] as $index=>&$item) {
            if(!is_array($item))throw new Problem(422,'Invalid category row.');Input::keys($item,['ref','label','channels','hidden','position']);
            $ref=EnrollmentProof::hex($item['ref']??null,64);$label=Input::text($item['label']??null,'category label',80);Input::integer($item['channels']??null,0,10000000);
            if(isset($refs[$ref])||!is_bool($item['hidden']??null)||($item['position']??null)!==($page-1)*50+$index+1)throw new Problem(422,'Invalid/duplicate category row.');
            $refs[$ref]=true;
            if(preg_match('~[a-z][a-z0-9+.-]*://|www\.|bearer\s|(?:password|passwd|username|token|secret|authorization|activation[_ -]?code|mac)\s*[:=]~i',$label))$label='[redacted category label]';
            $item['label']=mb_substr(preg_replace('/[\x00-\x1F\x7F]/u',' ',Secrets::redact($label)),0,80);
        }
        unset($item);
        if(strlen(json_encode($input,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR))>30000)throw new Problem(422,'Category snapshot is too large.');
        return $input;
    }
}
