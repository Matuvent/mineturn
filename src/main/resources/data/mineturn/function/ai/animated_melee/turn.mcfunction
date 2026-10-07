execute store result score @s mineturn.ai run mineturn ai target nearest_enemy
execute if score @s mineturn.ai matches 0 run return run mineturn ai wait
$execute store result score @s mineturn.ai run mineturn ai query ready $(action)
$execute if score @s mineturn.ai matches 1 run return run mineturn ai use $(action)
execute store result score @s mineturn.ai run mineturn ai query in_attack_range
execute if score @s mineturn.ai matches 1 run return run mineturn ai wait
execute store result score @s mineturn.ai run mineturn ai query engaged
execute if score @s mineturn.ai matches 1 run mineturn ai retreat
return run mineturn ai move_async toward_target
