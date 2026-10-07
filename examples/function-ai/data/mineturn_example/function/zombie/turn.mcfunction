# 当前生物就是 @s；没有目标时结束这个回合。
execute store result score @s mte_range run mineturn ai target nearest_enemy
execute if score @s mte_range matches 0 run return run mineturn ai wait
execute store result score @s mte_range run mineturn ai query in_attack_range
execute if score @s mte_range matches 0 run return run function mineturn_example:zombie/chase
execute store result score @s mte_roll run random value 1..100
$execute if score @s mte_roll matches 1..$(claw_weight) run return run mineturn ai use mineturn:claw
return run mineturn ai use mineturn:bite
