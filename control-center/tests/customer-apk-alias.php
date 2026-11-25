<?php
declare(strict_types=1);
require dirname(__DIR__).'/src/BrandMainUpdates.php';
$input=['app_version'=>'2.0.137','source_sha'=>str_repeat('a',40)];
$commit=['sha'=>$input['source_sha']];
$apk=['name'=>'ExtremeTV-2.0.137.apk','state'=>'uploaded','size'=>123,'digest'=>'sha256:'.str_repeat('b',64)];
$alias=array_replace($apk,['name'=>'ExtremeTV.apk']);
$release=['tag_name'=>'v2.0.137','draft'=>false,'prerelease'=>false,'published_at'=>'2026-11-24T09:14:45Z'];
$cases=[
    [[$apk],true],[[$apk,$alias],true],[[$alias,$apk],true],
    [[$alias],false],[[$apk,$apk],false],[[],false],
    [[$apk,array_replace($alias,['digest'=>'sha256:'.str_repeat('c',64)])],false],
    [[$apk,array_replace($alias,['size'=>124])],false],
    [[$apk,array_replace($alias,['state'=>'new'])],false],
    [[$apk,array_replace($alias,['name'=>'Other.apk'])],false],
    [[$apk,$alias,$alias],false],
];
foreach($cases as [$assets,$expected]){
    if(ControlCenter\BrandMainUpdates::publishedCustomerMatches($input,$release+['assets'=>$assets],$commit)!==$expected)throw new RuntimeException('Asset validation regression');
}
foreach([['draft'=>true],['prerelease'=>true],['published_at'=>null],['tag_name'=>'v2.0.136']] as $override){
    if(ControlCenter\BrandMainUpdates::publishedCustomerMatches($input,array_replace($release,$override,['assets'=>[$apk,$alias]]),$commit))throw new RuntimeException('Release validation regression');
}
if(ControlCenter\BrandMainUpdates::publishedCustomerMatches($input,$release+['assets'=>[$apk,$alias]],['sha'=>str_repeat('d',40)]))throw new RuntimeException('Source validation regression');
echo "16 customer APK alias checks passed.\n";
