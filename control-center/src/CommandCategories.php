<?php
declare(strict_types=1);
namespace ControlCenter;

/** Closed paged metadata schema; never source configuration, channel URLs or files. */
final class CommandCategories
{
    public static function payload(string $kind,array $input): array
    {
        if(in_array($kind,['get_categories','get_category_layout'],true)) {
            Input::keys($input,$kind==='get_categories'?['profile_ref','source_ref','page']:['profile_ref','source_ref']);
            foreach(['profile_ref','source_ref'] as $field)if(array_key_exists($field,$input))EnrollmentProof::hex($input[$field],64);
            if(isset($input['profile_ref'])!==isset($input['source_ref']))throw new Problem(422,'Select a source together with its profile.');
            if(array_key_exists('page',$input))Input::integer($input['page'],1,40);
        } elseif($kind==='apply_category_layout') {
            Input::keys($input,['profile_ref','source_ref','expected_revision','order','hidden','provider_order']);
            foreach(['profile_ref','source_ref','expected_revision'] as $field)EnrollmentProof::hex($input[$field]??null,64);
            foreach(['order','hidden'] as $field) {
                $refs=$input[$field]??null;
                if(!is_array($refs)||!array_is_list($refs)||count($refs)>2000||($field==='order'&&!$refs)||count(array_unique($refs,SORT_REGULAR))!==count($refs))throw new Problem(422,'Invalid complete layout references.');
                foreach($refs as $ref)EnrollmentProof::hex($ref,64);
            }
            if(array_diff($input['hidden'],$input['order']))throw new Problem(422,'Hidden categories must belong to this layout.');
            if(array_key_exists('provider_order',$input)&&!is_bool($input['provider_order']))throw new Problem(422,'Invalid provider-order flag.');
            $input['provider_order']=$input['provider_order']??false;
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
        $full=$kind==='get_category_layout';
        if(!in_array($kind,['get_categories','get_category_layout'],true)||!is_array($input)||array_is_list($input))throw new Problem(422,'Invalid category snapshot.');
        $keys=['schema_version','profile_ref','source_ref','revision','page','pages','total','order_editable','sources','items'];
        if($full)$keys=array_merge($keys,['page_size','provider_order']);
        Input::keys($input,$keys);if(array_diff($keys,array_keys($input)))throw new Problem(422,'Missing category snapshot fields.');
        if($input['schema_version']!==1||!is_bool($input['order_editable']))throw new Problem(422,'Invalid category snapshot version.');
        foreach(['profile_ref','source_ref','revision'] as $field)EnrollmentProof::hex($input[$field],64);
        $size=$full?2000:50;
        if($full&&($input['page_size']!==2000||!is_bool($input['provider_order'])))throw new Problem(422,'Invalid full-layout format.');
        $total=Input::integer($input['total'],1,2000);$page=Input::integer($input['page'],1,$full?1:40);$pages=(int)ceil($total/$size);
        if($input['pages']!==$pages||$page>$pages)throw new Problem(422,'Invalid category pagination.');
        if(!is_array($input['sources'])||!array_is_list($input['sources'])||count($input['sources'])<1||count($input['sources'])>32)throw new Problem(422,'Invalid category sources.');
        $refs=[];$selected=false;
        foreach($input['sources'] as $source) {
            if(!is_array($source))throw new Problem(422,'Invalid category source.');Input::keys($source,['ref','label','total']);
            $ref=EnrollmentProof::hex($source['ref']??null,64);$label=Input::text($source['label']??null,'source label',20);$count=Input::integer($source['total']??null,0,2000);
            if(!preg_match('/^(Playlist|Portal) [1-9][0-9]?$/D',$label)||isset($refs[$ref]))throw new Problem(422,'Invalid/duplicate category source.');
            $refs[$ref]=true;if($ref===$input['source_ref']){$selected=true;if($count!==$total)throw new Problem(422,'Category source count mismatch.');}
        }
        if(!$selected||!is_array($input['items'])||!array_is_list($input['items'])||count($input['items'])!==min($size,$total-($page-1)*$size))throw new Problem(422,'Invalid category page items.');
        $refs=[];$positions=[];$names=[];
        foreach($input['items'] as $index=>&$item) {
            if(!is_array($item))throw new Problem(422,'Invalid category row.');Input::keys($item,$full?['ref','label','channels','hidden','position','provider_position','match_name']:['ref','label','channels','hidden','position']);
            $ref=EnrollmentProof::hex($item['ref']??null,64);$label=Input::text($item['label']??null,'category label',80);Input::integer($item['channels']??null,0,10000000);
            if(isset($refs[$ref])||!is_bool($item['hidden']??null)||($item['position']??null)!==($page-1)*$size+$index+1)throw new Problem(422,'Invalid/duplicate category row.');
            $refs[$ref]=true;
            if(preg_match('~[a-z][a-z0-9+.-]*://|www\.|bearer\s|(?:password|passwd|username|token|secret|authorization|activation[_ -]?code|mac)\s*[:=]~i',$label))$label='[redacted category label]';
            $item['label']=mb_substr(preg_replace('/[\x00-\x1F\x7F]/u',' ',Secrets::redact($label)),0,80);
            if($full) {
                $position=Input::integer($item['provider_position']??null,1,$total);
                if(isset($positions[$position])||!array_key_exists('match_name',$item))throw new Problem(422,'Invalid provider order.');$positions[$position]=true;
                if($item['match_name']!==null) {
                    $name=self::portableName($item['match_name']);
                    if($name!==$item['label']||isset($names[$name]))throw new Problem(422,'Ambiguous portable category name.');$names[$name]=true;
                }
            }
        }
        unset($item);
        if(strlen(json_encode($input,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR))>($full?2000000:30000))throw new Problem(422,'Category snapshot is too large.');
        return $input;
    }
    public static function portableName(mixed $value): string
    {
        $name=Input::text($value,'portable category name',80);
        if(preg_match('~[a-z][a-z0-9+.-]*://|www\.|bearer\s|(?:password|passwd|username|token|secret|authorization|activation[_ -]?code|mac)\s*[:=]~i',$name)||preg_match('/[\x00-\x1F\x7F]/u',$name)||$name==='[redacted category label]')throw new Problem(422,'This category name cannot be used in a portable template.');
        return $name;
    }
}
