import type { DensityItem } from '../../types/density'
import { EXCHANGE_COLORS } from '../../utils/constants'
import { formatVolumeUsd, formatPrice, formatDistance, formatDuration, isNew } from '../../utils/formatters'
import { Badge } from '../common/Badge'

interface DensityRowProps {
  density: DensityItem
  newBadgeMinutes: number
}

export function DensityRow({ density, newBadgeMinutes }: DensityRowProps) {
  const color = EXCHANGE_COLORS[density.exchange]
  const showNew = isNew(density.firstSeenAt, newBadgeMinutes)
  const isBid = density.side === 'BID'

  return (
    <div className="flex items-center gap-3 px-3 py-2 hover:bg-white/5 rounded-lg transition-colors group">
      {/* Иконка биржи — цветная точка с названием */}
      <div className="flex items-center gap-1.5 w-24 shrink-0">
        <div
          className="w-2 h-2 rounded-full shrink-0"
          style={{ backgroundColor: color }}
        />
        <span className="text-xs text-gray-400 truncate">
          {density.exchange}
        </span>
        <span className="text-[10px] text-gray-600">
          {density.marketType === 'FUTURES' ? 'F' : 'S'}
        </span>
      </div>

      {/* Объём */}
      <span className="text-sm font-semibold text-white w-20 shrink-0">
        {formatVolumeUsd(density.volumeUsd)}
      </span>

      {/* Цена */}
      <span className={`text-sm font-mono w-24 shrink-0 ${isBid ? 'text-green-400' : 'text-red-400'}`}>
        {formatPrice(density.price)}
      </span>

      {/* Расстояние */}
      <span className="text-xs text-gray-400 w-14 shrink-0">
        {formatDistance(density.distancePercent)}
      </span>

      {/* Время жизни */}
      <span className="text-xs text-gray-500 w-14 shrink-0">
        {formatDuration(density.durationSeconds)}
      </span>

      {/* NEW бейдж */}
      {showNew && <Badge label="NEW" color="blue" />}
    </div>
  )
}
